package id.nusastudio.vault;

final class NativeKdf {
  static {
    System.loadLibrary("nusacrypto");
  }

  private NativeKdf() {}

  static native byte[] derive(byte[] password, byte[] salt);
}
