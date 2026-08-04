# Anti-fraud source policy

Xiaoling uses an authored, versioned local rule set. Public agencies are used to
identify scam patterns and safety guidance; their pages are not scraped into the
APK and are not treated as live blocklists.

Reference families include INTERPOL financial-fraud guidance, the US FTC and
FBI IC3 scam taxonomies, Europol online-fraud guidance, the UK NCSC phishing
guidance, Australian Scamwatch alerts, and Singapore ScamShield advisories.
Availability, copyright, API terms and regional access differ, so none of these
sites is a mandatory runtime dependency.

Runtime rules:

- High-risk phrase, conversation and URL-shape checks run locally.
- URLs are analyzed structurally and are not submitted to a third party.
- Phone-number reputation APIs are disabled unless both `XL_NUMBER_API_KEY` and
  an operator-selected `XL_NUMBER_API_URL` are configured.
- Remote rule updates must be served from the operator's HTTPS endpoint and
  should be reviewed, versioned and signed before production use.
- No data source can guarantee complete coverage or zero false positives. The
  spoken warning therefore recommends stopping payment and independently
  verifying through an official number instead of claiming certainty.

The optional character source reviewed for this release was Quaternius, whose
official asset pages state CC0. No external character file is shipped in this
release; the existing lightweight character is animated locally to avoid a
large 3D dependency and overseas CDN access.
