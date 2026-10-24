package id.nusastudio.vault;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A real content provider serving only synthetic test documents. Never packaged in the app. */
public final class TestDocuments extends DocumentsProvider {
  private static final String[] COLUMNS = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE
  };

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    MatrixCursor c =
        new MatrixCursor(
            new String[] {
              DocumentsContract.Root.COLUMN_ROOT_ID,
              DocumentsContract.Root.COLUMN_DOCUMENT_ID,
              DocumentsContract.Root.COLUMN_TITLE,
              DocumentsContract.Root.COLUMN_FLAGS
            });
    c.addRow(
        new Object[] {
          "root", "root", "Nusa test documents", DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        });
    return c;
  }

  private Object[] row(String id) throws FileNotFoundException {
    return switch (id) {
      case "root" -> new Object[] {id, "Test folder", DocumentsContract.Document.MIME_TYPE_DIR, 0};
      case "nested" -> new Object[] {id, "Subfolder", DocumentsContract.Document.MIME_TYPE_DIR, 0};
      case "a" -> new Object[] {id, "a.txt", "text/plain", 13};
      case "b" -> new Object[] {id, "b.txt", "text/plain", 13};
      default -> throw new FileNotFoundException();
    };
  }

  @Override
  public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
    MatrixCursor c = new MatrixCursor(COLUMNS);
    c.addRow(row(id));
    return c;
  }

  @Override
  public Cursor queryChildDocuments(String parent, String[] projection, String sort)
      throws FileNotFoundException {
    MatrixCursor c = new MatrixCursor(COLUMNS);
    if (parent.equals("root")) {
      c.addRow(row("a"));
      c.addRow(row("nested"));
    } else if (parent.equals("nested")) c.addRow(row("b"));
    else throw new FileNotFoundException();
    return c;
  }

  @Override
  public boolean isChildDocument(String parent, String child) {
    return parent.equals("root")
            && (child.equals("a") || child.equals("b") || child.equals("nested"))
        || parent.equals("nested") && child.equals("b");
  }

  @Override
  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation)
      throws FileNotFoundException {
    if (!mode.equals("r") || !(id.equals("a") || id.equals("b"))) throw new FileNotFoundException();
    File file = new File(getContext().getCacheDir(), "synthetic-" + id);
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write(("test-folder-" + id).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new FileNotFoundException(e.getMessage());
    }
    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
  }
}
