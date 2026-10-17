import com.sun.net.httpserver.HttpServer;
import expo.modules.etherdrop.UpdateDownloader;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;

public class UpdateDownloaderTest {
  public static void main(String[] args) throws Exception {
    byte[] payload = new byte[256 * 1024];
    for (int index = 0; index < payload.length; index++) payload[index] = (byte) (index * 31);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/update.apk", exchange -> {
      exchange.sendResponseHeaders(200, payload.length);
      try (var output = exchange.getResponseBody()) { output.write(payload); }
    });
    server.createContext("/failure", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
    server.start();
    File folder = Files.createTempDirectory("etherdrop-update-test-").toFile();
    File target = new File(folder, "EtherDrop.apk");
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      StringBuilder digest = new StringBuilder("sha256:");
      for (byte value : MessageDigest.getInstance("SHA-256").digest(payload)) digest.append(String.format("%02x", value & 0xff));
      var progress = new ArrayList<Long>();
      UpdateDownloader.download(base + "/update.apk", payload.length, digest.toString(), target, (done, total, speed) -> progress.add(done));
      require(Arrays.equals(payload, Files.readAllBytes(target.toPath())), "Downloaded bytes differ");
      require(progress.get(0) == 0 && progress.get(progress.size() - 1) == payload.length, "Missing download progress");
      require(!new File(target + ".part").exists(), "Partial file remains after success");
      expectFailure(base + "/update.apk", payload.length + 1L, digest.toString(), target, "incomplete");
      expectFailure(base + "/update.apk", payload.length, "sha256:wrong", target, "checksum");
      expectFailure(base + "/failure", payload.length, "", target, "HTTP 503");
      require(Arrays.equals(payload, Files.readAllBytes(target.toPath())), "A failed download destroyed the previous verified APK");
      UpdateDownloader.download(base + "/update.apk", payload.length, "", target, (done, total, speed) -> {});
      require(Arrays.equals(payload, Files.readAllBytes(target.toPath())), "Replacement download differs");
      System.out.println("Updater download checks passed: bytes, progress, size, SHA-256, HTTP errors, cleanup, and replacement.");
    } finally {
      server.stop(0);
      new File(target + ".part").delete();
      target.delete();
      folder.delete();
    }
  }

  private static void expectFailure(String url, long size, String digest, File target, String message) throws Exception {
    try {
      UpdateDownloader.download(url, size, digest, target, (done, total, speed) -> {});
      throw new AssertionError("Expected " + message + " failure");
    } catch (IOException error) {
      require(error.getMessage().contains(message), error.getMessage());
      require(!new File(target + ".part").exists(), "Partial download was not removed");
    }
  }

  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
