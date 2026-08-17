package ai.xdev.aisdlc.service;

import static ai.xdev.aisdlc.service.SeasonalCostForecaster.ADVISORY;
import static ai.xdev.aisdlc.service.SeasonalCostForecaster.INSUFFICIENT_DATA;
import static ai.xdev.aisdlc.service.SeasonalCostForecaster.LOW_CONFIDENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.xdev.aisdlc.service.SeasonalCostForecaster.DailyCost;
import ai.xdev.aisdlc.service.SeasonalCostForecaster.Forecast;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The forecast is a pure function, so its rules are checked directly rather than through a database.
 *
 * <p>Every date here is fixed. A forecast test seeded from {@code LocalDate.now()} passes or fails depending on which
 * day of the week it runs, which is exactly the property under test.
 */
class SeasonalCostForecasterTest {
  private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30); // a Tuesday

  @Test void tooFewObservedDaysRefusesWithTheShortfallNamedInsteadOfProjectingFromNoise() {
    List<DailyCost> sparse = new ArrayList<>();
    for (int back = 0; back < 10; back++) sparse.add(new DailyCost(AS_OF.minusDays(back), 1_000));

    Forecast forecast = SeasonalCostForecaster.forecast(sparse, AS_OF, 7);

    assertEquals(INSUFFICIENT_DATA, forecast.status());
    assertNull(forecast.predictedMinor(), "a refusal must not carry a number");
    assertEquals(10, forecast.observedDays());
    assertTrue(forecast.explanation().contains("28"), "the explanation names the threshold: " + forecast.explanation());
    assertTrue(forecast.explanation().contains("10"), "and the actual count: " + forecast.explanation());
  }

  @Test void aHorizonCoveringAWeekdayWithTooLittleHistoryRefusesRatherThanGuessingThatDay() {
    // 40 weekdays observed, but only two Sundays. A seasonal model with two Sundays has not learned Sundays, and a
    // 7-day horizon necessarily covers one.
    List<DailyCost> weekdaysOnly = new ArrayList<>();
    for (int back = 0; back < 56; back++) {
      LocalDate date = AS_OF.minusDays(back);
      if (date.getDayOfWeek() == DayOfWeek.SUNDAY && back > 14) continue;
      weekdaysOnly.add(new DailyCost(date, 5_000));
    }

    Forecast forecast = SeasonalCostForecaster.forecast(weekdaysOnly, AS_OF, 7);

    assertEquals(INSUFFICIENT_DATA, forecast.status());
    assertTrue(forecast.explanation().contains("SUNDAY"), forecast.explanation());
  }

  @Test void theBaselineIsSeasonalRatherThanFlat() {
    // Weekdays cost 10_000, weekends 0. A flat mean would predict 10_000 * 5/7 for every day; the seasonal baseline
    // must predict the weekday level on weekdays and zero on weekends.
    Forecast forecast = SeasonalCostForecaster.forecast(history(date -> weekday(date) ? 10_000L : 0L), AS_OF, 7);

    assertEquals(ADVISORY, forecast.status());
    // A full week covers exactly five weekdays.
    assertEquals(50_000, forecast.predictedMinor());

    // A single Saturday horizon must predict zero, which a flat mean could never do.
    LocalDate friday = LocalDate.of(2026, 6, 26);
    Forecast weekend = SeasonalCostForecaster.forecast(history(date -> weekday(date) ? 10_000L : 0L), friday, 1);
    assertEquals(0, weekend.predictedMinor());
  }

  @Test void aStableSeriesBackTestsCleanlyAndKeepsItsIntervals() {
    Forecast forecast = SeasonalCostForecaster.forecast(history(date -> weekday(date) ? 10_000L : 0L), AS_OF, 7);

    assertNotNull(forecast.backtest());
    assertEquals(4, forecast.backtest().folds());
    assertEquals(0d, forecast.backtest().weightedAbsolutePercentageError(), 1e-9, "a perfectly regular series has no error");
    assertEquals(1.0d, forecast.backtest().intervalCoverage(), 1e-9);
    assertEquals(ADVISORY, forecast.status());
    assertTrue(forecast.lowerMinor() <= forecast.predictedMinor() && forecast.predictedMinor() <= forecast.upperMinor());
  }

  @Test void aSeriesWhoseIntervalsDidNotHoldIsMarkedLowConfidenceButStillReported() {
    // A hard level shift partway through the window: the residual band learned before the shift cannot contain the
    // days after it, so coverage falls below the threshold.
    Forecast forecast = SeasonalCostForecaster.forecast(
        history(date -> date.isAfter(AS_OF.minusDays(21)) ? 900_000L : 1_000L), AS_OF, 7);

    assertEquals(LOW_CONFIDENCE, forecast.status());
    assertNotNull(forecast.predictedMinor(), "a low-confidence forecast stays visible; hiding it would hide the evidence");
    assertTrue(forecast.backtest().intervalCoverage() < SeasonalCostForecaster.MIN_INTERVAL_COVERAGE);
    assertTrue(forecast.explanation().contains("must not drive a recommendation"), forecast.explanation());
  }

  @Test void aPeriodThatCostNothingReportsNoPercentageErrorRatherThanAPerfectOne() {
    // Zero actual cost makes the WAPE denominator zero. Reporting 0% would read as a flawless forecast.
    Forecast forecast = SeasonalCostForecaster.forecast(history(date -> 0L), AS_OF, 7);

    assertEquals(0, forecast.predictedMinor());
    assertNull(forecast.backtest().weightedAbsolutePercentageError());
    assertEquals(0L, forecast.backtest().medianAbsoluteErrorMinor());
  }

  @Test void boundsAreNeverNegativeBecauseCostCannotBe() {
    Forecast forecast = SeasonalCostForecaster.forecast(
        history(date -> weekday(date) ? (date.getDayOfMonth() % 2 == 0 ? 20_000L : 1_000L) : 0L), AS_OF, 14);

    assertTrue(forecast.lowerMinor() >= 0, "lower bound was " + forecast.lowerMinor());
  }

  @Test void theHorizonIsBoundedSoAnAbsurdRequestCannotProduceAnAbsurdProjection() {
    Forecast huge = SeasonalCostForecaster.forecast(history(date -> weekday(date) ? 10_000L : 0L), AS_OF, Integer.MAX_VALUE);
    Forecast ninety = SeasonalCostForecaster.forecast(history(date -> weekday(date) ? 10_000L : 0L), AS_OF, 90);

    assertEquals(ninety.predictedMinor(), huge.predictedMinor());
  }

  private static boolean weekday(LocalDate date) {
    return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
  }

  /** 84 days of history ending at {@link #AS_OF}, which is what the service fetches for a four-fold back-test. */
  private static List<DailyCost> history(java.util.function.Function<LocalDate, Long> cost) {
    List<DailyCost> days = new ArrayList<>();
    for (int back = 83; back >= 0; back--) {
      LocalDate date = AS_OF.minusDays(back);
      days.add(new DailyCost(date, cost.apply(date)));
    }
    return days;
  }
}
