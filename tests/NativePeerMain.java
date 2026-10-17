import expo.modules.etherdrop.EtherDropEngine;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Runs the production mobile engine on the JVM; only Android's document provider is replaced. */
public class NativePeerMain {
  static EtherDropEngine engine;
  static volatile boolean disconnected;
  static synchronized void emit(JSONObject value) { System.out.println(value); System.out.flush(); }
  static String signature(Path file) throws IOException { return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis(); }
  static class Storage implements EtherDropEngine.Storage {
    public List<EtherDropEngine.Source> scan(List<String> selected) throws Exception {
      List<EtherDropEngine.Source> result = new ArrayList<>();
      for (String token : selected) {
        Path root = Paths.get(token);
        if (Files.isDirectory(root)) {
          try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>)paths.filter(Files::isRegularFile).sorted()::iterator)
              result.add(new EtherDropEngine.Source(path.toString(), root.getFileName() + "/" + root.relativize(path).toString().replace('\\', '/'), Files.size(path), NativePeerMain.signature(path)));
          }
        } else result.add(new EtherDropEngine.Source(token, root.getFileName().toString(), Files.size(root), NativePeerMain.signature(root)));
      }
      return result;
    }
    public InputStream read(EtherDropEngine.Source source) throws Exception { return Files.newInputStream(Paths.get(source.token)); }
    public String signature(EtherDropEngine.Source source) throws Exception { return NativePeerMain.signature(Paths.get(source.token)); }
    public EtherDropEngine.Target destination(String token, String peer, boolean wrap) throws Exception {
      Path root = Paths.get(token); Files.createDirectories(root);
      Path output = wrap ? Files.createDirectory(root.resolve("EtherDrop-test-" + System.nanoTime())) : root;
      return new EtherDropEngine.Target() {
        final List<Path> created = new ArrayList<>();
        public String location() { return output.toString(); }
        public long totalSpace() { return output.toFile().getTotalSpace(); }
        public long freeSpace() { return output.toFile().getFreeSpace(); }
        public OutputStream write(String name, long offset) throws Exception {
          Path target = output.resolve(EtherDropEngine.safePath(name));
          Files.createDirectories(target.getParent());
          if (!Files.exists(target)) created.add(target);
          RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw"); file.setLength(offset); file.seek(offset);
          return new OutputStream() {
            public void write(int value) throws IOException { file.write(value); }
            public void write(byte[] bytes, int start, int count) throws IOException { file.write(bytes, start, count); }
            public void close() throws IOException { file.close(); }
          };
        }
        public String signature(String name) throws Exception { return NativePeerMain.signature(output.resolve(name)); }
        public void cancel() throws Exception {
          for (Path path : created) Files.deleteIfExists(path);
          try (java.util.stream.Stream<Path> paths = Files.walk(output)) {
            for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder()).filter(Files::isDirectory)::iterator)
              if (!path.equals(root)) try { Files.delete(path); } catch (DirectoryNotEmptyException ignored) {}
          }
        }
      };
    }
  }
  public static void main(String[] args) throws Exception {
    engine = new EtherDropEngine(new Storage(), () -> emit(EtherDropEngine.jsonObject("event", "state", "state", engine.snapshot())));
    try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
      String line;
      while ((line = input.readLine()) != null) {
        try {
          JSONObject command = new JSONObject(line);
          String op = command.getString("op");
          switch (op) {
            case "start": engine.start(command.getString("role"), new EtherDropEngine.Network(command.getString("address"), 8, "Test Wi-Fi", "Android engine", new EtherDropEngine.Binding() {
              public void bind(java.net.Socket socket) throws Exception { if (disconnected) throw new java.net.SocketException("Wi-Fi disconnected"); }
              public void bind(java.net.DatagramSocket socket) throws Exception { if (disconnected) throw new java.net.SocketException("Wi-Fi disconnected"); }
            })); break;
            case "connection": disconnected = !command.getBoolean("connected"); break;
            case "peer": engine.acceptAnnouncement(EtherDropEngine.jsonObject("magic", EtherDropEngine.MAGIC, "version", 3, "mode", "wifi", "session", command.optString("session", "desktop"), "role", "receiver", "hostname", command.optString("name", "Windows PC")), command.getString("address")); break;
            case "send": {
              List<String> paths = new ArrayList<>(); JSONArray selected = command.getJSONArray("selected");
              for (int i = 0; i < selected.length(); i++) paths.add(selected.getString(i));
              engine.send(paths, command.optBoolean("verify", true), command.optBoolean("wrap", true)); break;
            }
            case "accept": engine.accept(command.getString("id"), command.getString("destination")); break;
            case "decline": engine.decline(command.getString("id")); break;
            case "cancel": engine.cancel(command.getString("id")); break;
            case "cancelAll": engine.cancelAll(); break;
            case "dismiss": engine.dismiss(); break;
            case "state": break;
            case "stop": engine.stop(); break;
            case "quit": return;
            default: throw new IllegalArgumentException(op);
          }
          emit(EtherDropEngine.jsonObject("event", "reply", "op", op, "state", engine.snapshot()));
        } catch (Exception error) { emit(EtherDropEngine.jsonObject("event", "error", "message", error.toString())); }
      }
    } finally { engine.close(); }
  }
}
