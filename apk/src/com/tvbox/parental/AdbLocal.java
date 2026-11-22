package com.tvbox.parental;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal ADB client that talks to the box's own adbd on 127.0.0.1:5555.
 *
 * This firmware reports ro.adb.secure=0, so the connection needs no RSA
 * pairing. Going through adbd gives us the shell UID, which is allowed to
 * inject key events and request a reboot - things a normal app UID cannot do.
 */
public final class AdbLocal {

    private static final int CNXN = 0x4e584e43;
    private static final int AUTH = 0x48545541;
    private static final int OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f;
    private static final int WRTE = 0x45545257;
    private static final int CLSE = 0x45534c43;

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;

    private AdbLocal() { }

    private static byte[] pkt(int cmd, int a0, int a1, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        long sum = 0;
        for (byte x : data) {
            sum += (x & 0xff);
        }
        b.putInt(cmd);
        b.putInt(a0);
        b.putInt(a1);
        b.putInt(data.length);
        b.putInt((int) (sum & 0xffffffffL));
        b.putInt(cmd ^ 0xffffffff);
        b.put(data);
        return b.array();
    }

    private static final class Msg {
        int cmd, a0, a1;
        byte[] data;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("adb eof");
            }
            off += r;
        }
        return buf;
    }

    private static Msg readMsg(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(readFully(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.a0 = b.getInt();
        m.a1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // crc
        b.getInt(); // magic
        if (len < 0 || len > 4 * 1024 * 1024) {
            throw new IOException("bad adb length " + len);
        }
        m.data = len > 0 ? readFully(in, len) : new byte[0];
        return m;
    }

    public static String shell(String command) {
        return run("shell:" + command, true);
    }

    /** Fire the adbd reboot service. The device restarts, so no reply is expected. */
    public static void reboot() {
        run("reboot:", false);
    }

    private static String run(String service, boolean collect) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(HOST, PORT), 3000);
            s.setSoTimeout(collect ? 6000 : 1200);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            out.write(pkt(CNXN, 0x01000000, 256 * 1024,
                    "host::\0".getBytes(StandardCharsets.US_ASCII)));
            out.flush();

            Msg hello = readMsg(in);
            if (hello.cmd == AUTH) {
                throw new IOException("adb auth required");
            }
            if (hello.cmd != CNXN) {
                throw new IOException("bad handshake " + Integer.toHexString(hello.cmd));
            }

            out.write(pkt(OPEN, 1, 0, service.getBytes(StandardCharsets.UTF_8)));
            out.flush();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            for (int i = 0; i < 40; i++) {
                Msg m = readMsg(in);
                if (m.cmd == WRTE) {
                    buf.write(m.data);
                    out.write(pkt(OKAY, m.a1, m.a0, new byte[0]));
                    out.flush();
                    if (!collect) {
                        break;
                    }
                } else if (m.cmd == CLSE) {
                    out.write(pkt(CLSE, m.a1, m.a0, new byte[0]));
                    out.flush();
                    break;
                } else if (m.cmd == OKAY) {
                    if (!collect) {
                        break;
                    }
                } else {
                    break;
                }
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // A timeout on the reboot service is expected: the box is already
            // restarting and will never send the closing packet.
            return "";
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) { }
            }
        }
    }
}
