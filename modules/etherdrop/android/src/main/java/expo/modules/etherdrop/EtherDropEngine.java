package expo.modules.etherdrop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** The desktop V3 wire protocol. No Android dependencies: exercised against the Python app. */
public final class EtherDropEngine implements Closeable {
  public static final int DISCOVERY_PORT = 45670, TRANSFER_PORT = 45671;
  public static final int BLOCK_SIZE = 8 * 1024 * 1024;
  public static final String MAGIC = "ETHERDROP_V3";
  public interface Storage {
    List<Source> scan(List<String> selected) throws Exception;
    InputStream read(Source source) throws Exception;
    String signature(Source source) throws Exception;
    Target destination(String token, String peer, boolean wrap) throws Exception;
  }
  public interface Target {
    String location();
    long totalSpace();
    long freeSpace();
    OutputStream write(String path, long offset) throws Exception;
    String signature(String path) throws Exception;
    void cancel() throws Exception;
  }
  public interface Binding {
    void bind(Socket socket) throws Exception;
    void bind(DatagramSocket socket) throws Exception;
  }
  public static final Binding DEFAULT_BINDING = new Binding() {
    public void bind(Socket socket) {}
    public void bind(DatagramSocket socket) {}
  };
  public static final class Source {
    public final String token, path, signature;
    public final long size;
    public Source(String token, String path, long size, String signature) {
      this.token = token; this.path = safePath(path); this.size = size; this.signature = signature;
      if (size < 0) throw new IllegalArgumentException("File size is unavailable: " + path);
    }
  }
  public static final class Network {
    public final String address, name, device;
    public final int prefix;
    public final Binding binding;
    public Network(String address, int prefix, String name, String device, Binding binding) {
      this.address = address; this.prefix = prefix; this.name = name; this.device = device; this.binding = binding;
    }
    long mask() { return prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL; }
    boolean contains(String ip) { return (ipv4(ip) & mask()) == (ipv4(address) & mask()); }
    String broadcast() {
      long ip = ipv4(address) | (~mask() & 0xffffffffL);
      return ((ip >> 24) & 255) + "." + ((ip >> 16) & 255) + "." + ((ip >> 8) & 255) + "." + (ip & 255);
    }
    JSONObject json() { return jsonObject("address", address, "prefix", prefix, "name", name, "device", device, "broadcast", broadcast()); }
  }
  private static final class Peer {
    String id, name, ip, role;
    long seen;
    JSONObject json() { return jsonObject("id", id, "name", name, "ip", ip, "role", role); }
  }
  private static final class Transfer {
    final String id, peer, ip, direction;
    final ReentrantLock lock = new ReentrantLock();
    final CompletableFuture<String> approval = new CompletableFuture<>();
    final Map<String, String> saved = new LinkedHashMap<>();
    final List<String> log = new ArrayList<>();
    volatile Channel channel;
    volatile boolean cancelled, finished;
    volatile JSONObject result;
    volatile JSONObject offer;
    Target target;
    MessageDigest hasher;
    volatile String current = "", phase = "Connecting", message = "", destination = "";
    volatile long total, done, offset, started, ended, updated;
    volatile int count, index;
    volatile boolean verify, wrap;
    Transfer(String id, String peer, String ip, String direction) {
      this.id = id; this.peer = peer; this.ip = ip; this.direction = direction;
    }
    synchronized JSONObject json() {
      double elapsed = started == 0 ? 0 : ((ended == 0 ? System.nanoTime() : ended) - started) / 1e9;
      double speed = elapsed > 0 ? done / elapsed : 0;
      return jsonObject("id", id, "peer", peer, "ip", ip, "direction", direction, "phase", phase,
        "message", message, "destination", destination, "total", total, "done", done, "count", count,
        "index", index, "current", current, "verify", verify, "wrap", wrap, "finished", finished,
        "awaitingApproval", direction.equals("Receiving") && !approval.isDone() && !finished,
        "elapsed", elapsed, "speed", speed, "eta", speed > 0 ? (total - done) / speed : -1,
        "roots", offer == null ? new JSONArray() : offer.optJSONArray("roots"), "log", new JSONArray(log));
    }
  }
  private final Storage storage;
  private final Runnable changed;
  private final String session = UUID.randomUUID().toString();
  private final Map<String, Peer> peers = new LinkedHashMap<>();
  private final Map<String, Transfer> transfers = new LinkedHashMap<>();
  private final ExecutorService workers = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "etherdrop"); t.setDaemon(true); return t; });
  private volatile Network network;
  private volatile String role = "", error = "";
  private volatile DatagramSocket discovery;
  private volatile ServerSocket server;
  private volatile int generation;
  private Transfer incoming;

  public EtherDropEngine(Storage storage, Runnable changed) { this.storage = storage; this.changed = changed; }
  public synchronized JSONObject snapshot() {
    JSONArray p = new JSONArray(), t = new JSONArray();
    for (Peer peer : peers.values()) p.put(peer.json());
    for (Transfer transfer : transfers.values()) t.put(transfer.json());
    return jsonObject("role", role, "network", network == null ? JSONObject.NULL : network.json(), "error", error, "peers", p, "transfers", t);
  }
  public synchronized boolean busy() { for (Transfer t : transfers.values()) if (!t.finished) return true; return false; }
  public synchronized void start(String nextRole, Network nextNetwork) throws Exception {
    if (busy()) throw new IllegalStateException("Finish the current transfer before changing the connection.");
    stopNetwork();
    role = nextRole; network = nextNetwork; error = ""; peers.clear();
    int current = generation;
    if (role.equals("receiver")) {
      ServerSocket listener = new ServerSocket();
      listener.setReuseAddress(true);
      listener.bind(new InetSocketAddress(network.address, TRANSFER_PORT));
      server = listener;
      workers.execute(() -> serve(listener, current));
    }
    DatagramSocket listener = new DatagramSocket(null);
    listener.setReuseAddress(true); listener.setBroadcast(true);
    listener.bind(new InetSocketAddress(DISCOVERY_PORT)); listener.setSoTimeout(250);
    network.binding.bind(listener);
    discovery = listener;
    workers.execute(() -> discover(listener, current));
    changed.run();
  }
  public synchronized void setError(String message) { error = message; changed.run(); }
  private synchronized void stopNetwork() {
    generation++;
    if (discovery != null) discovery.close();
    if (server != null) try { server.close(); } catch (IOException ignored) {}
    discovery = null; server = null;
  }
  public void stop() {
    synchronized (this) { stopNetwork(); role = ""; peers.clear(); }
    cancelAll(); changed.run();
  }
  @Override public void close() { stop(); workers.shutdown(); }
  private void discover(DatagramSocket listener, int current) {
    Network n = network;
    try (DatagramSocket announce = new DatagramSocket(new InetSocketAddress(n.address, 0))) {
      announce.setBroadcast(true);
      byte[] buffer = new byte[4096]; long last = 0; boolean disconnected = false;
      while (generation == current) {
        long now = System.currentTimeMillis();
        if (now - last >= 1000) {
          byte[] bytes = jsonObject("magic", MAGIC, "version", 3, "mode", "wifi", "session", session,
            "hostname", n.device, "role", role, "transfer_port", TRANSFER_PORT).toString().getBytes(StandardCharsets.UTF_8);
          try {
            n.binding.bind(announce);
            n.binding.bind(listener);
            announce.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName(n.broadcast()), DISCOVERY_PORT));
            if (disconnected) { disconnected = false; setError(""); }
          } catch (IOException e) {
            if (!disconnected) { disconnected = true; setError("Wi-Fi disconnected. Waiting to reconnect to the same network."); }
          }
          last = now;
        }
        try {
          DatagramPacket packet = new DatagramPacket(buffer, buffer.length); listener.receive(packet);
          acceptAnnouncement(new JSONObject(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8)), packet.getAddress().getHostAddress());
        } catch (SocketTimeoutException ignored) {
        } catch (org.json.JSONException | IllegalArgumentException ignored) {}
        synchronized (this) {
          boolean expired = peers.values().removeIf(p -> System.currentTimeMillis() - p.seen > 4000);
          if (expired) changed.run();
        }
      }
    } catch (Exception e) { if (generation == current) setError("Discovery failed: " + message(e)); }
  }
  public synchronized void acceptAnnouncement(JSONObject msg, String ip) {
    String otherRole = msg.optString("role"), id = msg.optString("session");
    if (network == null || !MAGIC.equals(msg.optString("magic")) || msg.optInt("version") != 3 ||
      !"wifi".equals(msg.optString("mode")) || id.isEmpty() || session.equals(id) || otherRole.equals(role) ||
      !(otherRole.equals("sender") || otherRole.equals("receiver")) || !network.contains(ip)) return;
    Peer p = peers.get(id); boolean added = p == null;
    if (p == null) { p = new Peer(); p.id = id; peers.put(id, p); }
    p.name = msg.optString("hostname", "Other device"); p.ip = ip; p.role = otherRole; p.seen = System.currentTimeMillis();
    if (added) changed.run();
  }
  public synchronized void send(List<String> selected, boolean verify, boolean wrap) {
    if (!role.equals("sender") || network == null) throw new IllegalStateException("Choose Send files first.");
    if (!transfers.isEmpty()) throw new IllegalStateException("Tap Done on the previous transfer first.");
    List<Transfer> recipients = new ArrayList<>();
    for (Peer p : peers.values()) {
      Transfer t = new Transfer(UUID.randomUUID().toString().replace("-", ""), p.name, p.ip, "Sending");
      t.verify = verify; t.wrap = wrap; transfers.put(t.id, t); recipients.add(t);
    }
    if (recipients.isEmpty()) throw new IllegalStateException("No receivers are connected.");
    workers.execute(() -> {
      try {
        for (Transfer t : recipients) phase(t, "Scanning selected files", "Reading names and sizes.");
        List<Source> files = storage.scan(selected);
        if (files.isEmpty()) throw new IOException("No transferable files were selected.");
        for (Transfer t : recipients) workers.execute(() -> sendWorker(t, files));
      } catch (Exception e) { for (Transfer t : recipients) finish(t, "Failed", message(e)); }
    });
    changed.run();
  }
  private Channel connect(String ip) throws Exception {
    Socket socket = new Socket();
    try {
      network.binding.bind(socket); socket.bind(new InetSocketAddress(network.address, 0));
      socket.connect(new InetSocketAddress(ip, TRANSFER_PORT), 5000);
      return new Channel(socket);
    } catch (IOException e) { socket.close(); throw new NetworkFailure(e); }
    catch (Exception e) { socket.close(); throw e; }
  }
  private void sendWorker(Transfer t, List<Source> files) {
    boolean offered = false;
    try {
      Set<String> roots = new LinkedHashSet<>(); StringBuilder manifest = new StringBuilder();
      for (Source f : files) { t.total += f.size; roots.add(f.path.split("/")[0]); manifest.append(f.path).append('\0').append(f.size).append('\0').append(f.signature).append('\n'); }
      t.count = files.size();
      t.offer = jsonObject("magic", MAGIC, "version", 3, "type", "offer", "transfer_id", t.id,
        "hostname", network.device, "manifest", hex(MessageDigest.getInstance("SHA-256").digest(manifest.toString().getBytes(StandardCharsets.UTF_8))),
        "total_bytes", t.total, "file_count", t.count, "verify", t.verify, "wrap", t.wrap, "roots", new JSONArray(roots));
      boolean retry = false;
      while (true) {
        checkCancelled(t);
        try (Channel channel = connect(t.ip)) {
          t.channel = channel;
          channel.send(new JSONObject(t.offer.toString()).put("resume", t.started != 0)); offered = true;
          BlockingQueue<Object> replies = new LinkedBlockingQueue<>();
          workers.execute(() -> {
            try { while (true) { JSONObject reply = channel.read(); replies.put(reply); String kind = reply.optString("type"); if (Arrays.asList("cancel", "error", "reject", "done_ok").contains(kind)) return; } }
            catch (Exception e) { replies.offer(e); }
          });
          if (t.started == 0) phase(t, "Waiting for receiver", "The receiver must choose a destination folder and accept.");
          JSONObject reply = response(replies, t);
          if (reply.optString("type").equals("done_ok")) break;
          require(reply.optString("type").equals("accept"), "Invalid receiver response.");
          t.index = reply.getInt("index"); long offset = reply.getLong("offset"); t.done = reply.getLong("done");
          require(t.index >= 0 && t.index <= files.size() && offset >= 0 && (t.index == files.size() ? offset == 0 : offset <= files.get(t.index).size), "Invalid resume position.");
          t.destination = reply.optString("destination");
          if (retry) for (Source f : files) require(f.signature.equals(storage.signature(f)), "Source file changed: " + f.path);
          if (t.started == 0) t.started = System.nanoTime();
          phase(t, "Transferring", retry ? "Continuing from saved progress." : "Sending files directly over Wi-Fi.");
          retry = false;
          byte[] buffer = new byte[BLOCK_SIZE];
          for (int i = t.index; i < files.size(); i++) {
            Source f = files.get(i); checkCancelled(t);
            require(f.signature.equals(storage.signature(f)), "Source file changed: " + f.path);
            t.current = f.path; t.index = i + 1; log(t, "Sending " + f.path);
            MessageDigest digest = t.verify ? MessageDigest.getInstance("SHA-256") : null;
            try (InputStream input = storage.read(f)) {
              long skipped = 0;
              while (skipped < offset) {
                checkCancelled(t); int got = input.read(buffer, 0, (int)Math.min(buffer.length, offset - skipped));
                if (got < 0) throw new IOException("Source file changed: " + f.path);
                if (digest != null) digest.update(buffer, 0, got); skipped += got;
              }
              channel.writeLock.lock();
              try {
                channel.send(jsonObject("type", "file", "path", f.path, "size", f.size, "offset", offset));
                long position = offset;
                while (position < f.size) {
                  checkCancelled(t); int got = input.read(buffer, 0, (int)Math.min(buffer.length, f.size - position));
                  if (got < 0) throw new IOException("Source file changed: " + f.path);
                  if (digest != null) digest.update(buffer, 0, got);
                  channel.writeBytes(buffer, got); position += got; t.done += got; progress(t);
                }
                channel.flush();
                if (digest != null) channel.send(jsonObject("type", "hash", "sha256", hex(digest.digest())));
              } finally { channel.writeLock.unlock(); }
            }
            require(f.signature.equals(storage.signature(f)), "Source file changed: " + f.path);
            require(response(replies, t).optString("type").equals("file_ok"), "The receiver did not confirm " + f.path);
            offset = 0;
          }
          channel.send(jsonObject("type", "done"));
          phase(t, "Waiting for receiver", "The receiver is finishing saving the files.");
          require(response(replies, t).optString("type").equals("done_ok"), "The receiver did not confirm completion.");
          break;
        } catch (NetworkFailure e) {
          checkCancelled(t); retry = true;
          phase(t, "Paused", "Connection interrupted. Waiting to reconnect — keep both apps open.");
          Thread.sleep(1000);
        } finally { t.channel = null; }
      }
      t.done = t.total; finish(t, "Completed", "All files sent successfully.");
    } catch (Declined e) { finish(t, "Declined", message(e));
    } catch (Cancelled e) {
      if (offered && t.cancelled) notifyStop(t, false);
      finish(t, "Cancelled", message(e));
    } catch (Exception e) {
      if (offered) notifyStop(t, true);
      finish(t, "Failed", message(e));
    }
  }
  private JSONObject response(BlockingQueue<Object> replies, Transfer t) throws Exception {
    while (true) {
      checkCancelled(t); Object value = replies.poll(250, TimeUnit.MILLISECONDS); if (value == null) continue;
      if (value instanceof Exception) throw (Exception)value;
      JSONObject reply = (JSONObject)value; String type = reply.optString("type");
      if (type.equals("cancel")) throw new Cancelled(reply.optString("reason", "The receiver cancelled."));
      if (type.equals("reject")) throw new Declined(reply.optString("reason", "The receiver declined."));
      if (type.equals("error")) throw new IllegalStateException(reply.optString("reason", "The receiver could not save the files."));
      return reply;
    }
  }
  private void notifyStop(Transfer t, boolean failed) {
    do {
      try (Channel channel = connect(t.ip)) {
        channel.send(jsonObject("magic", MAGIC, "version", 3, "transfer_id", t.id, "type", failed ? "abort" : "cancel", "reason", "Transfer stopped by the sender."));
        JSONObject result = channel.read();
        if (result.optString("type").equals("error")) log(t, result.optString("reason"));
        return;
      } catch (Exception e) {
        if (failed || role.isEmpty()) { log(t, "Receiver unreachable; remove paused output on the receiving device."); return; }
        phase(t, "Cancelling", "Waiting to reconnect so the receiver can remove new output.");
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
      }
    } while (true);
  }
  private void serve(ServerSocket listener, int current) {
    try {
      while (generation == current) {
        Socket socket = listener.accept();
        if (!network.contains(socket.getInetAddress().getHostAddress())) { socket.close(); continue; }
        workers.execute(() -> receiveConnection(socket));
      }
    } catch (Exception e) { if (generation == current) setError("Receiver failed: " + message(e)); }
  }
  private void receiveConnection(Socket socket) {
    Transfer t = null;
    try (Channel channel = new Channel(socket)) {
      JSONObject offer = channel.read();
      require(MAGIC.equals(offer.optString("magic")) && offer.optInt("version") == 3, "Both devices need compatible EtherDrop versions.");
      String id = offer.getString("transfer_id"), ip = socket.getInetAddress().getHostAddress(), type = offer.optString("type");
      synchronized (this) {
        boolean matched = incoming != null && incoming.id.equals(id) && incoming.ip.equals(ip);
        if (type.equals("cancel") || type.equals("abort")) {
          if (matched) t = incoming;
        } else if (!type.equals("offer")) throw new IllegalArgumentException("Invalid EtherDrop connection.");
        else if (matched) {
          t = incoming;
          for (String key : Arrays.asList("manifest", "total_bytes", "file_count", "verify", "wrap"))
            require(Objects.equals(t.offer.opt(key), offer.opt(key)), "The transfer contents changed.");
        } else if (offer.optBoolean("resume")) {
          channel.send(jsonObject("type", "reject", "reason", "This transfer is no longer available. Send it again.")); return;
        } else if (!transfers.isEmpty()) {
          channel.send(jsonObject("type", "reject", "reason", "This device is handling another transfer. Tap Done before sending again.")); return;
        } else {
          t = new Transfer(id, offer.optString("hostname", "Other device"), ip, "Receiving");
          t.offer = offer; t.total = offer.getLong("total_bytes"); t.count = offer.getInt("file_count");
          require(t.total >= 0 && t.count >= 0, "Invalid transfer size.");
          t.verify = offer.getBoolean("verify"); t.wrap = offer.optBoolean("wrap", true);
          incoming = t; transfers.put(id, t);
          phase(t, "Incoming transfer", "Review these files and choose where to save them.");
        }
      }
      if (type.equals("cancel") || type.equals("abort")) {
        if (t == null) { channel.send(jsonObject("type", "cancel", "reason", "No output created.")); return; }
        cancel(t.id); t.lock.lock();
        try { channel.send(t.result == null ? jsonObject("type", "cancel") : t.result); } finally { t.lock.unlock(); }
        return;
      }
      t.lock.lock();
      try {
        if (t.result != null) { channel.send(t.result); return; }
        t.channel = channel;
        try { receiveFiles(t, channel); }
        catch (NetworkFailure e) { if (t.cancelled) cleanup(t); else phase(t, "Paused", "Connection interrupted. Waiting to reconnect — keep both apps open."); }
        catch (Cancelled e) { cleanup(t); try { channel.send(t.result); } catch (IOException ignored) {} }
        catch (Declined e) { t.result = jsonObject("type", "reject", "reason", message(e)); finish(t, "Declined", message(e)); channel.send(t.result); }
        catch (Exception e) { t.result = jsonObject("type", "error", "reason", message(e)); finish(t, "Failed", message(e)); channel.send(t.result); }
        finally { t.channel = null; }
      } finally { t.lock.unlock(); }
    } catch (Exception e) { if (t != null && !t.finished && !t.cancelled) phase(t, "Paused", "Connection interrupted. Waiting to reconnect."); }
  }
  private void receiveFiles(Transfer t, Channel channel) throws Exception {
    while (!t.approval.isDone()) {
      checkCancelled(t); JSONObject pending = channel.poll(200);
      if (pending != null) require(pending.optString("type").equals("ping"), "Expected receiver approval.");
    }
    checkCancelled(t); String destination = t.approval.get();
    if (destination.isEmpty()) throw new Declined("Transfer declined by the receiver.");
    if (t.target == null) { t.target = storage.destination(destination, t.peer, t.wrap); t.destination = t.target.location(); t.started = System.nanoTime(); }
    for (Map.Entry<String, String> saved : t.saved.entrySet()) require(saved.getValue().equals(t.target.signature(saved.getKey())), "Received file changed during interruption: " + saved.getKey());
    phase(t, "Transferring", "Receiving files directly over Wi-Fi.");
    channel.send(jsonObject("type", "accept", "destination", t.destination, "disk_total", t.target.totalSpace(), "disk_free", t.target.freeSpace(), "index", t.index, "offset", t.offset, "done", t.done));
    byte[] buffer = new byte[BLOCK_SIZE];
    while (t.index < t.count) {
      checkCancelled(t); JSONObject meta = channel.read(); require(meta.optString("type").equals("file"), "Expected file metadata.");
      String path = safePath(meta.getString("path")); long size = meta.getLong("size");
      require(size >= 0 && meta.optLong("offset") == t.offset && size <= t.total - t.done + t.offset, "Invalid file size or resume position.");
      if (!t.current.isEmpty()) require(path.equals(t.current), "The resumed file changed.");
      else { t.current = path; t.hasher = t.verify ? MessageDigest.getInstance("SHA-256") : null; }
      log(t, "Receiving " + path);
      try (OutputStream output = t.target.write(path, t.offset)) {
        while (t.offset < size) {
          checkCancelled(t); int wanted = (int)Math.min(buffer.length, size - t.offset);
          channel.readBytes(buffer, wanted);
          output.write(buffer, 0, wanted);
          if (t.hasher != null) t.hasher.update(buffer, 0, wanted);
          t.offset += wanted; t.done += wanted; progress(t);
        }
      } finally { t.saved.put(path, t.target.signature(path)); }
      if (t.verify) { JSONObject hash = channel.read(); require(hash.optString("type").equals("hash") && hex(t.hasher.digest()).equals(hash.optString("sha256")), "SHA-256 verification failed: " + path); }
      t.index++; t.offset = 0; t.current = ""; t.hasher = null;
      channel.send(jsonObject("type", "file_ok"));
    }
    require(channel.read().optString("type").equals("done"), "Transfer did not end correctly.");
    checkCancelled(t); require(t.done == t.total, "The received size did not match the offer.");
    t.result = jsonObject("type", "done_ok"); finish(t, "Completed", "All files received successfully."); channel.send(t.result);
  }
  public synchronized void accept(String id, String destination) { Transfer t = transfers.get(id); if (t != null) t.approval.complete(destination); }
  public synchronized void decline(String id) { Transfer t = transfers.get(id); if (t != null) t.approval.complete(""); }
  public void cancel(String id) {
    Transfer t; synchronized (this) { t = transfers.get(id); }
    if (t == null || t.finished) return;
    t.cancelled = true; t.approval.complete("");
    Channel channel = t.channel;
    if (channel != null) {
      if (t.direction.equals("Receiving") && channel.writeLock.tryLock()) {
        try { channel.send(jsonObject("type", "cancel", "reason", "Transfer cancelled by the receiver.")); }
        catch (IOException ignored) {} finally { channel.writeLock.unlock(); }
      }
      channel.close();
    }
    if (t.direction.equals("Receiving")) workers.execute(() -> { t.lock.lock(); try { cleanup(t); } finally { t.lock.unlock(); } });
  }
  public synchronized void cancelAll() { for (String id : new ArrayList<>(transfers.keySet())) cancel(id); }
  public synchronized void dismiss() {
    if (busy()) throw new IllegalStateException("Wait for transfer cleanup to finish.");
    transfers.clear(); incoming = null; changed.run();
  }
  private void cleanup(Transfer t) {
    if (t.finished) return;
    try { if (t.target != null) t.target.cancel(); t.result = jsonObject("type", "cancel", "reason", "New transfer output removed."); finish(t, "Cancelled", "New transfer output removed."); }
    catch (Exception e) { t.result = jsonObject("type", "error", "reason", "Could not remove new output: " + message(e)); finish(t, "Failed", t.result.optString("reason")); }
  }
  private static void checkCancelled(Transfer t) throws Cancelled { if (t.cancelled) throw new Cancelled("Transfer cancelled."); }
  private void phase(Transfer t, String phase, String message) { synchronized (t) { if (t.finished) return; if (!phase.equals(t.phase) || !message.equals(t.message)) log(t, phase + ": " + message); t.phase = phase; t.message = message; } changed.run(); }
  private void finish(Transfer t, String phase, String message) { synchronized (t) { if (t.finished) return; t.phase = phase; t.message = message; t.ended = System.nanoTime(); t.finished = true; log(t, phase + ": " + message); } changed.run(); }
  private void progress(Transfer t) { long now = System.currentTimeMillis(); if (now - t.updated >= 150) { t.updated = now; changed.run(); } }
  private void log(Transfer t, String text) { synchronized (t) { t.log.add(new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + text); if (t.log.size() > 250) t.log.remove(0); } }
  private static final class Declined extends Exception { Declined(String message) { super(message); } }
  private static final class Cancelled extends Exception { Cancelled(String message) { super(message); } }
  private static final class NetworkFailure extends IOException { NetworkFailure(IOException cause) { super(cause.getMessage(), cause); } }
  private static final class Channel implements Closeable {
    final Socket socket;
    final DataInputStream input;
    final DataOutputStream output;
    final ReentrantLock writeLock = new ReentrantLock();
    final ScheduledExecutorService heartbeat;
    Channel(Socket socket) throws IOException {
      this.socket = socket; socket.setTcpNoDelay(true); socket.setSoTimeout(15000);
      input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
      output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
      heartbeat = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "etherdrop-heartbeat"); t.setDaemon(true); return t; });
      heartbeat.scheduleWithFixedDelay(() -> { if (writeLock.tryLock()) try { send(jsonObject("type", "ping")); } catch (IOException e) { close(); } finally { writeLock.unlock(); } }, 2, 2, TimeUnit.SECONDS);
    }
    void send(JSONObject value) throws IOException {
      byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 1024 * 1024) throw new IllegalArgumentException("Control frame too large.");
      writeLock.lock(); try { output.writeInt(bytes.length); output.write(bytes); output.flush(); }
      catch (IOException e) { throw new NetworkFailure(e); } finally { writeLock.unlock(); }
    }
    void writeBytes(byte[] bytes, int count) throws NetworkFailure { try { output.write(bytes, 0, count); } catch (IOException e) { throw new NetworkFailure(e); } }
    void flush() throws NetworkFailure { try { output.flush(); } catch (IOException e) { throw new NetworkFailure(e); } }
    void readBytes(byte[] bytes, int count) throws NetworkFailure { try { input.readFully(bytes, 0, count); } catch (IOException e) { throw new NetworkFailure(e); } }
    JSONObject frame() throws Exception {
      try {
        int size = input.readInt(); require(size > 0 && size <= 1024 * 1024, "Invalid control frame.");
        byte[] bytes = new byte[size]; input.readFully(bytes); return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
      } catch (IOException e) { throw new NetworkFailure(e); }
    }
    JSONObject read() throws Exception { while (true) { JSONObject value = frame(); if (!value.optString("type").equals("ping")) return value; } }
    JSONObject poll(int millis) throws Exception {
      input.mark(1); socket.setSoTimeout(millis);
      try { if (input.read() < 0) throw new EOFException("Connection closed."); input.reset(); }
      catch (SocketTimeoutException e) { return null; }
      catch (IOException e) { throw new NetworkFailure(e); }
      finally { socket.setSoTimeout(15000); }
      return frame();
    }
    @Override public void close() { heartbeat.shutdownNow(); try { socket.close(); } catch (IOException ignored) {} }
  }
  public static String safePath(String path) {
    if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Unsafe transfer path.");
    for (String part : path.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Unsafe transfer path.");
    return path;
  }
  private static long ipv4(String ip) { String[] parts = ip.split("\\."); if (parts.length != 4) throw new IllegalArgumentException("Invalid IPv4 address."); long result = 0; for (String p : parts) { int octet = Integer.parseInt(p); if (octet < 0 || octet > 255) throw new IllegalArgumentException("Invalid IPv4 address."); result = (result << 8) | octet; } return result; }
  public static JSONObject jsonObject(Object... values) {
    JSONObject json = new JSONObject();
    try { for (int i = 0; i < values.length; i += 2) json.put((String)values[i], values[i + 1]); }
    catch (org.json.JSONException e) { throw new IllegalArgumentException(e); }
    return json;
  }
  private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
  private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(); for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255)); return text.toString(); }
  private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
