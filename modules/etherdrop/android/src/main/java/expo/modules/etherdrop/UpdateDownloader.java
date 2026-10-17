package expo.modules.etherdrop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.security.MessageDigest;

public final class UpdateDownloader {
  public interface Progress { void update(long done, long total, double speed); }

  public static File download(String url, long size, String expectedDigest, File target, Progress progress) throws Exception {
    File partial = new File(target.getPath() + ".part");
    HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
    connection.setConnectTimeout(15000);
    connection.setReadTimeout(30000);
    connection.setRequestProperty("User-Agent", "EtherDrop-Mobile");
    connection.setRequestProperty("Accept-Encoding", "identity");
    boolean completed = false;
    try {
      if (connection.getResponseCode() != 200) throw new IOException("The update download failed (HTTP " + connection.getResponseCode() + ").");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long done = 0, lastDone = 0, lastTime = System.nanoTime();
      progress.update(0, size, 0);
      try (var input = connection.getInputStream(); var output = new FileOutputStream(partial)) {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (Thread.currentThread().isInterrupted()) throw new IOException("The update download was canceled.");
          output.write(buffer, 0, count);
          digest.update(buffer, 0, count);
          done += count;
          long now = System.nanoTime();
          if (now - lastTime >= 250_000_000L || done == size) {
            progress.update(done, size, (done - lastDone) * 1_000_000_000.0 / Math.max(1, now - lastTime));
            lastTime = now;
            lastDone = done;
          }
        }
      }
      if (done == 0 || done != size) throw new IOException("The update download is incomplete. Please try again.");
      StringBuilder actualDigest = new StringBuilder("sha256:");
      for (byte value : digest.digest()) actualDigest.append(String.format("%02x", value & 0xff));
      if (!expectedDigest.isEmpty() && !expectedDigest.equals(actualDigest.toString())) throw new IOException("The update checksum does not match the GitHub release.");
      if (target.exists() && !target.delete()) throw new IOException("Cannot replace the previous update download.");
      if (!partial.renameTo(target)) throw new IOException("Cannot save the downloaded update.");
      completed = true;
      return target;
    } finally {
      connection.disconnect();
      if (!completed) partial.delete();
    }
  }
}
