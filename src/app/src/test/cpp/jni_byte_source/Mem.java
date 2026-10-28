import com.audiophile.engine.library.source.RemoteByteSource;
public class Mem implements RemoteByteSource {
  byte[] data; public static volatile int closed = 0;
  public Mem(int n) { data = new byte[n]; for (int i = 0; i < n; i++) data[i] = (byte)(i * 31 + 7); }
  public long size() { return data.length; }
  public int readAt(long off, java.nio.ByteBuffer dst) {
    if (off == 13) throw new RuntimeException("boom");
    if (off == 17) return -1;
    if (off >= data.length) return 0;
    int n = (int)Math.min(dst.remaining(), data.length - off);
    dst.put(data, (int)off, n); return n; }
  public void close() { closed++; }
}
