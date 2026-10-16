package chat.keryx.core.demo

/**
 * The pages the demo's agent writes (3.0 herald): what a `MEDIA:` artifact opens on, served from
 * the phone because there's no gateway to fetch it from. Self-contained HTML, no scripts and
 * nothing fetched, so the viewer shows it exactly as a real one, offline.
 *
 * The numbers agree with [DemoScript]'s importer session: 2,104 photos, read_exif 41.2 s,
 * copy 3.1 s, 38 tests.
 */
object DemoPages {
    const val SPEEDUP = "~/photo-importer/speedup.html"

    /** The page at [path], or null when the demo never wrote one there. */
    fun page(path: String): String? = when (path) {
        SPEEDUP -> SPEEDUP_HTML
        else -> null
    }

    private val SPEEDUP_HTML = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Photo importer: before and after</title>
<style>
  :root {
    --bg: #faf6ee; --card: #ffffff; --ink: #2b2620; --soft: #6f665a; --line: #e6dccb;
    --exif: #c8892b; --copy: #9db3a4; --good: #3f7d4e;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #12110f; --card: #1c1a17; --ink: #ece6dc; --soft: #a59c8e; --line: #2e2a25;
      --exif: #e0a548; --copy: #7f9c8a; --good: #79b88a;
    }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 24px 16px 40px; background: var(--bg); color: var(--ink);
    font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  }
  main { max-width: 640px; margin: 0 auto; }
  .eyebrow { color: var(--soft); font-size: 13px; letter-spacing: .08em; text-transform: uppercase; margin: 0; }
  h1 { font-size: 30px; line-height: 1.15; margin: 6px 0 4px; }
  h1 b { color: var(--exif); }
  .sub { color: var(--soft); margin: 0 0 24px; }
  section { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 18px; margin-bottom: 14px; }
  h2 { font-size: 15px; margin: 0 0 14px; }
  .row { display: grid; grid-template-columns: 56px 1fr 58px; gap: 10px; align-items: center; margin: 10px 0; }
  .label { font-weight: 600; font-size: 14px; }
  .time { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; }
  .bar { display: flex; height: 22px; border-radius: 6px; overflow: hidden; background: var(--line); }
  .exif { background: var(--exif); }
  .copy { background: var(--copy); }
  .legend { display: flex; gap: 16px; color: var(--soft); font-size: 13px; margin-top: 12px; flex-wrap: wrap; }
  .dot { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 6px; vertical-align: -1px; }
  ul { margin: 0; padding-left: 20px; }
  li { margin: 6px 0; }
  code { font: 13px/1.4 ui-monospace, "SF Mono", Menlo, monospace; background: var(--line); padding: 1px 5px; border-radius: 4px; }
  .pass { color: var(--good); font-weight: 700; }
  .stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; text-align: center; }
  .stat b { display: block; font-size: 22px; font-variant-numeric: tabular-nums; }
  .stat span { color: var(--soft); font-size: 12px; }
  footer { color: var(--soft); font-size: 12px; text-align: center; margin-top: 22px; }
</style>
</head>
<body>
<main>
  <p class="eyebrow">Photo importer</p>
  <h1><b>6.5×</b> faster</h1>
  <p class="sub">2,104 photos from <code>~/cards/sample</code>, timed with <code>--dry-run</code>.</p>

  <section>
    <h2>Time to import the card</h2>
    <div class="row">
      <span class="label">Before</span>
      <div class="bar" role="img" aria-label="Before: 41.2 seconds reading EXIF, 3.1 seconds copying">
        <div class="exif" style="width: 93%"></div><div class="copy" style="width: 7%"></div>
      </div>
      <span class="time">44.3 s</span>
    </div>
    <div class="row">
      <span class="label">After</span>
      <div class="bar" role="img" aria-label="After: 3.7 seconds reading EXIF, 3.1 seconds copying">
        <div class="exif" style="width: 8.4%"></div><div class="copy" style="width: 7%"></div>
      </div>
      <span class="time">6.8 s</span>
    </div>
    <div class="legend">
      <span><i class="dot" style="background: var(--exif)"></i>Reading EXIF</span>
      <span><i class="dot" style="background: var(--copy)"></i>Copying files</span>
    </div>
  </section>

  <section class="stats">
    <div class="stat"><b>44.3 s</b><span>before</span></div>
    <div class="stat"><b>6.8 s</b><span>after</span></div>
    <div class="stat"><b class="pass">38/38</b><span>tests pass</span></div>
  </section>

  <section>
    <h2>What changed</h2>
    <ul>
      <li>EXIF used to be read one photo at a time: <code>read_exif(path)</code>, 2,104 times. It's one <code>read_exif_batch(paths)</code> call for the whole card now.</li>
      <li>Copying is unchanged at 3.1 s. That's the card reader's own speed, so it's the floor.</li>
      <li>The tests ran against a fresh thumbnail cache, so the old one couldn't hide the change.</li>
    </ul>
  </section>

  <footer>Written by your agent, as a page you can open on your phone.</footer>
</main>
</body>
</html>
""".trimIndent()
}
