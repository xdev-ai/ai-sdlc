# Portal localization policy

## Scope

The AI-SDLC repository uses **English** for GitHub-facing and developer-facing material: documentation, source-code comments, API descriptions, release notes, issue templates, and operations guidance. The portal offers end-user interface support for **English (`en`)** and **Vietnamese (`vi`)**.

The only intended Vietnamese source text in the repository is the portal's translation resource at `portal/src/main/resources/static/js/locale.js`. It is product content that must be present in source control so the browser can render Vietnamese without a runtime translation service. It is not developer-facing documentation.

## Locale resolution

`PortalLocalizationConfig` uses Spring's `CookieLocaleResolver` with the `AISDLC_LOCALE` cookie. English is the default and fallback locale. A user can choose a locale from the portal language switcher; the switcher adds `?lang=en` or `?lang=vi`, Spring persists that selection for 365 days, and the current URL retains the selected locale for shareable navigation.

The portal renders `<html lang>` from the server locale. The SSR page remains useful without JavaScript. When JavaScript is available, `locale.js` translates the static portal shell and continues translating HTMX fragments and React Island nodes added after initial render. No localization data contains access tokens, project evidence, or other tenant data.

| Concern | Contract |
|---|---|
| Default and fallback | English (`en`) |
| Supported UI locale | Vietnamese (`vi`) |
| Preference persistence | `AISDLC_LOCALE` cookie, path `/`, 365-day lifetime |
| Server-side rendering | `CookieLocaleResolver` and `LocaleChangeInterceptor` on GET `lang` parameter |
| Progressive enhancement | The SSR English page is still complete without JavaScript; Vietnamese client translation activates only after the selected locale is rendered |
| React Islands | Consume the browser-local `AISDLC_I18N` translation contract; never own locale security state |
| API and CLI | Remain language-neutral; stable codes and RFC 9457 problem types are not localized |

## Adding a new UI string

Add the English source string to the relevant SSR template or React Island, then add its Vietnamese equivalent to the `vi` dictionary in `locale.js`. Keep identifiers, API values, role names, policy state values, model pins, SHA-256 values, and audit hashes unmodified. Use UTF-8 and include the correct diacritics.

Before merging, run the portal test suite and JavaScript build, then verify that the English fallback still works without client-side translation:

```bash
mvn -B -pl portal test
(cd portal/frontend && npm run build)
node --check portal/src/main/resources/static/js/locale.js
```
