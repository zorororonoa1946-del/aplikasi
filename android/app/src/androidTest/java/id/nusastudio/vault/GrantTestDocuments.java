package id.nusastudio.vault;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Grants access solely to synthetic test data, from its owning test application's UID. */
public final class GrantTestDocuments extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    grantUriPermission(
        "id.nusastudio.vault",
        DocumentsContract.buildTreeDocumentUri("id.nusastudio.vault.testdocuments", "root"),
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    finish();
  }
}
