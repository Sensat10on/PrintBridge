# File Printing And Export

How a document gets into PrintBridge, what it becomes, and how to get it back out.

## Getting a document in

Two entry points, both ending in the same pipeline:

1. **In the app** — the `ПЕЧАТЬ ФАЙЛА` section offers `ИЗОБРАЖЕНИЕ`, `PDF`, `ТЕКСТ` and
   `ЛЮБОЙ ФАЙЛ`, all through the system file picker.
2. **From another app** — PrintBridge appears in the share sheet for any file type. Choosing it
   opens the file section with the document already loaded. The intent carries only the URI, so a
   large file is read under the size limit rather than being copied into the intent.

When several files are shared at once only the first is loaded; the log records how many were
ignored. There is no batch queue yet.

## What a document becomes

| Source | Handling |
|---|---|
| Image (PNG/JPEG/WebP/BMP/GIF) | decoded with `inSampleSize`, scaled to the printer width, thresholded to 1 bit |
| PDF | rendered page by page with the platform `PdfRenderer` |
| Text / CSV | wrapped to the printer column count; encoding detected as BOM, then strict UTF-8, then Windows-1251 |

Raster pages go out through `GS v 0` (ESC/POS), `BITMAP` (TSPL) or the GOOJPRT bitmap command.
Text goes through the ESC/POS or TSPL builders, encoded for the protocol.

Limits: 8000 dots of height per rendered page, 50 PDF pages, 512 KB per text file.

## Sheet limit and watermark

The `paid` build prints one sheet per job and stamps a watermark until a licence or promo code is
present; the `full` test build does neither. See `docs/KNOWN_LIMITATIONS.md` for the entitlement
rules and `docs/RELEASE_PROCESS.md` for how the two builds are produced.

## Resuming a batch that stopped partway

Each sheet is a separate queue job, so a failure does not lose the rest of the document.
`BatchPrintResult` reports how far the batch got, the failure screen offers
`ПРОДОЛЖИТЬ С N-го ЛИСТА`, and a retry sends only the sheets that did not go through while keeping
the job names aligned with the document.

## Export

The export panel saves the current job where the user chooses: the destination comes from the
Storage Access Framework (`CreateDocument`), so both folder and file name are picked in the system
dialog and the app needs no storage permission. It writes nothing into a fixed directory.

| Format | Contents |
|---|---|
| PDF (страницы) | one PDF page per sheet, each printed dot rendered as a block |
| Сырые байты принтера | the exact bytes that would be sent to the printer (`.bin`) |
| Текст документа | the document text as read, for text and CSV sources |

The offered formats depend on the document: a raster job offers PDF and raw bytes, a text job
offers raw bytes and text.

## What is not verified

- Printing any of it on real hardware.
- The share entry point and the export dialog have not been exercised by hand on a device yet:
  `adb` cannot drive the share sheet or the Storage Access Framework picker reliably.
- `android.graphics.pdf.PdfDocument` has no Robolectric shadow, so unit tests cover the page
  geometry but the PDF bytes themselves are only produced by the device build.
