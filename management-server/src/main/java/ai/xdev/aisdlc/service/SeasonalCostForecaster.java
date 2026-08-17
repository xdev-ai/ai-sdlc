package ai.xdev.aisdlc.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The deterministic forecast described in {@code docs/p3-2-cost-optimization-and-inference-forecasting-design.md}
 * section 5: an eight-week trailing window, a day-of-week seasonal baseline, empirical residual intervals, and
 * rolling-origin back-testing over the four most recent complete weeks.
 *
 * <p>It replaces a trailing 28-day daily mean that had no seasonality and no back-testing. A flat mean is wrong in a
 * specific and predictable direction for this workload: inference cost follows working days, so a flat mean
 * under-predicts every weekday and over-predicts every weekend, and nothing in the previous output revealed that.
 *
 * <p>Deliberately a pure function over a list of daily totals. Forecast arithmetic that can only be exercised through
 * a database is arithmetic nobody checks; every rule below is asserted directly in {@code SeasonalCostForecasterTest}.
 *
 * <p>Three refusals are as important as the arithmetic:
 *
 * <ul>
 *   <li>Fewer than {@value #MIN_OBSERVED_DAYS} observed days returns {@link #INSUFFICIENT_DATA} with the shortfall
 *       named. It does not return a number computed from a handful of points.
 *   <li>A horizon that lands on a weekday with fewer than {@value #MIN_OBSERVATIONS_PER_DAY_OF_WEEK} observations
 *       also returns {@link #INSUFFICIENT_DATA}. A seasonal model with one Sunday in it has not learned Sundays.
 *   <li>Back-tested interval coverage below {@value #MIN_INTERVAL_COVERAGE} returns {@link #LOW_CONFIDENCE}. The
 *       number stays visible, because hiding it would hide the evidence that it is unreliable, but the status says
 *       the intervals did not hold historically.
 * </ul>
 */
public final class SeasonalCostForecaster {
  public static final String METHODOLOGY = "DOW_SEASONAL_8W_ROLLING_BACKTEST_V2";
  public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";
  public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
  public static final String ADVISORY = "ADVISORY";

  /** Eight complete weeks, so every day of the week contributes the same number of slots. */
  static final int WINDOW_DAYS = 56;
  static final int MIN_OBSERVED_DAYS = 28;
  static final int MIN_OBSERVATIONS_PER_DAY_OF_WEEK = 4;
  static final int BACKTEST_FOLDS = 4;
  static final double MIN_INTERVAL_COVERAGE = 0.80d;
  private static final double LOWER_PERCENTILE = 0.10d;
  private static final double UPPER_PERCENTILE = 0.90d;

  private SeasonalCostForecaster() {}

  /** One day that carried at least one usage event. A date absent from the input carried none. */
  public record DailyCost(LocalDate date, long minorUnits) {}

  /**
   * @param weightedAbsolutePercentageError null when every back-test fold had zero actual cost, because a percentage
   *     error with a zero denominator is undefined rather than perfect
   * @param intervalCoverage fraction of back-tested days whose actual cost fell inside the predicted interval
   */
  public record Backtest(int folds, Double weightedAbsolutePercentageError, Long medianAbsoluteErrorMinor,
                         Double intervalCoverage) {}

  public record Forecast(String status, Long predictedMinor, Long lowerMinor, Long upperMinor, int windowDays,
                         int observedDays, Backtest backtest, String explanation) {}

  public static Forecast forecast(List<DailyCost> history, LocalDate asOf, int horizonDays) {
    int horizon = Math.max(1, Math.min(90, horizonDays));
    Map<LocalDate, Long> observed = new HashMap<>();
    for (DailyCost day : history) if (day.date() != null) observed.merge(day.date(), day.minorUnits(), Long::sum);

    List<LocalDate> window = windowEndingAt(asOf, WINDOW_DAYS);
    int observedDays = (int) window.stream().filter(observed::containsKey).count();
    if (observedDays < MIN_OBSERVED_DAYS) {
      return new Forecast(INSUFFICIENT_DATA, null, null, null, WINDOW_DAYS, observedDays, null,
          "Needs " + MIN_OBSERVED_DAYS + " observed days in the trailing " + WINDOW_DAYS + "-day window; found " + observedDays + ".");
    }

    Map<DayOfWeek, Integer> counts = observationsPerDayOfWeek(window, observed);
    List<LocalDate> horizonDates = horizonStartingAfter(asOf, horizon);
    for (DayOfWeek needed : horizonDates.stream().map(LocalDate::getDayOfWeek).distinct().toList()) {
      int seen = counts.getOrDefault(needed, 0);
      if (seen < MIN_OBSERVATIONS_PER_DAY_OF_WEEK) {
        return new Forecast(INSUFFICIENT_DATA, null, null, null, WINDOW_DAYS, observedDays, null,
            "The horizon covers " + needed + ", which has only " + seen + " observations; "
                + MIN_OBSERVATIONS_PER_DAY_OF_WEEK + " are required.");
      }
    }

    Map<DayOfWeek, Double> baseline = seasonalBaseline(window, observed);
    List<Double> residuals = residuals(window, observed, baseline);
    double lowerResidual = percentile(residuals, LOWER_PERCENTILE);
    double upperResidual = percentile(residuals, UPPER_PERCENTILE);

    long predicted = 0;
    long lower = 0;
    long upper = 0;
    for (LocalDate date : horizonDates) {
      double mean = baseline.getOrDefault(date.getDayOfWeek(), 0d);
      predicted += Math.round(mean);
      // Cost cannot be negative, so a residual that would push a bound below zero is clamped rather than reported.
      lower += Math.round(Math.max(0d, mean + lowerResidual));
      upper += Math.round(Math.max(0d, mean + upperResidual));
    }

    Backtest backtest = backtest(observed, asOf);
    boolean coverageHeld = backtest.intervalCoverage() == null || backtest.intervalCoverage() >= MIN_INTERVAL_COVERAGE;
    String status = coverageHeld ? ADVISORY : LOW_CONFIDENCE;
    String explanation = coverageHeld
        ? "Day-of-week baseline over " + observedDays + " observed days; intervals from empirical residuals."
        : "Back-tested interval coverage " + String.format(java.util.Locale.ROOT, "%.2f", backtest.intervalCoverage())
            + " is below the required " + MIN_INTERVAL_COVERAGE + "; the projection is advisory only and must not drive a recommendation.";
    return new Forecast(status, predicted, lower, upper, WINDOW_DAYS, observedDays, backtest, explanation);
  }

  /**
   * Rolling-origin back-test. Fold {@code k} trains on the window ending {@code 7k} days before {@code asOf} and
   * predicts the seven days that followed, so no fold ever sees its own target.
   */
  private static Backtest backtest(Map<LocalDate, Long> observed, LocalDate asOf) {
    List<Long> absoluteErrors = new ArrayList<>();
    long totalAbsoluteError = 0;
    long totalActual = 0;
    int covered = 0;
    int scored = 0;
    int folds = 0;

    for (int fold = BACKTEST_FOLDS; fold >= 1; fold--) {
      LocalDate origin = asOf.minusDays(7L * fold);
      List<LocalDate> trainWindow = windowEndingAt(origin, WINDOW_DAYS);
      if (trainWindow.stream().noneMatch(observed::containsKey)) continue;
      Map<DayOfWeek, Double> baseline = seasonalBaseline(trainWindow, observed);
      List<Double> residuals = residuals(trainWindow, observed, baseline);
      double lowerResidual = percentile(residuals, LOWER_PERCENTILE);
      double upperResidual = percentile(residuals, UPPER_PERCENTILE);
      folds++;

      for (int offset = 1; offset <= 7; offset++) {
        LocalDate target = origin.plusDays(offset);
        long actual = observed.getOrDefault(target, 0L);
        double mean = baseline.getOrDefault(target.getDayOfWeek(), 0d);
        long predicted = Math.round(mean);
        long error = Math.abs(actual - predicted);
        absoluteErrors.add(error);
        totalAbsoluteError += error;
        totalActual += actual;
        scored++;
        if (actual >= Math.round(Math.max(0d, mean + lowerResidual)) && actual <= Math.round(Math.max(0d, mean + upperResidual))) covered++;
      }
    }

    if (scored == 0) return new Backtest(0, null, null, null);
    // A percentage error needs a non-zero denominator. Reporting 0% for a period that cost nothing would read as a
    // perfect forecast rather than as an absent one.
    Double wape = totalActual == 0 ? null : (double) totalAbsoluteError / (double) totalActual;
    return new Backtest(folds, wape, median(absoluteErrors), (double) covered / (double) scored);
  }

  private static Map<DayOfWeek, Double> seasonalBaseline(List<LocalDate> window, Map<LocalDate, Long> observed) {
    Map<DayOfWeek, List<Long>> byDay = new EnumMap<>(DayOfWeek.class);
    // A date in the window with no usage row is a real zero in the ledger, not a gap, so it counts towards the mean.
    for (LocalDate date : window) byDay.computeIfAbsent(date.getDayOfWeek(), unused -> new ArrayList<>()).add(observed.getOrDefault(date, 0L));
    Map<DayOfWeek, Double> baseline = new EnumMap<>(DayOfWeek.class);
    byDay.forEach((day, values) -> {
      OptionalDouble mean = values.stream().mapToLong(Long::longValue).average();
      baseline.put(day, mean.orElse(0d));
    });
    return baseline;
  }

  private static List<Double> residuals(List<LocalDate> window, Map<LocalDate, Long> observed, Map<DayOfWeek, Double> baseline) {
    List<Double> residuals = new ArrayList<>(window.size());
    for (LocalDate date : window) residuals.add(observed.getOrDefault(date, 0L) - baseline.getOrDefault(date.getDayOfWeek(), 0d));
    return residuals;
  }

  private static Map<DayOfWeek, Integer> observationsPerDayOfWeek(List<LocalDate> window, Map<LocalDate, Long> observed) {
    Map<DayOfWeek, Integer> counts = new EnumMap<>(DayOfWeek.class);
    for (LocalDate date : window) if (observed.containsKey(date)) counts.merge(date.getDayOfWeek(), 1, Integer::sum);
    return counts;
  }

  private static List<LocalDate> windowEndingAt(LocalDate end, int days) {
    List<LocalDate> window = new ArrayList<>(days);
    for (int back = days - 1; back >= 0; back--) window.add(end.minusDays(back));
    return window;
  }

  private static List<LocalDate> horizonStartingAfter(LocalDate asOf, int horizon) {
    List<LocalDate> dates = new ArrayList<>(horizon);
    for (int ahead = 1; ahead <= horizon; ahead++) dates.add(asOf.plusDays(ahead));
    return dates;
  }

  private static double percentile(List<Double> values, double fraction) {
    if (values.isEmpty()) return 0d;
    List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Double::compareTo);
    int index = (int) Math.round(fraction * (sorted.size() - 1));
    return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
  }

  private static Long median(List<Long> values) {
    if (values.isEmpty()) return null;
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(Long::compareTo);
    int middle = sorted.size() / 2;
    return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
  }
}
