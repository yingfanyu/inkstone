package com.inkstone.app;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 最小的 DNS TXT 查询实现。
 * Android 没有公开的 TXT 记录 API（DnsResolver 只对 API 29+ 且要 raw RR），
 * 这里直接按 RFC 1035 手撸 UDP 查询，超时退化到下一个公共 DNS。
 */
public final class DnsTxt {

    private static final String[] RESOLVERS = {"223.5.5.5", "119.29.29.29", "114.114.114.114", "1.1.1.1"};
    private static final int TYPE_TXT = 16;
    private static final int CLASS_IN = 1;

    private DnsTxt() {
    }

    /** 依次尝试各公共 DNS，返回所有 TXT 记录（每条记录内多段字符串已拼接）。 */
    public static List<String> lookup(String name, int budgetMs) {
        List<String> result = new ArrayList<String>();
        long deadline = System.currentTimeMillis() + budgetMs;
        for (String server : RESOLVERS) {
            int left = (int) (deadline - System.currentTimeMillis());
            if (left < 250) {
                break;
            }
            try {
                List<String> part = query(server, name, Math.min(left, 1800));
                if (!part.isEmpty()) {
                    return part;
                }
            } catch (Throwable ignored) {
                // 换下一个解析器
            }
        }
        return result;
    }

    private static List<String> query(String server, String name, int timeoutMs) throws Exception {
        int id = new Random().nextInt(0xFFFF);
        byte[] request = buildQuery(id, name);

        byte[] response = udp(server, request, timeoutMs);
        if (response == null) {
            return new ArrayList<String>();
        }
        int flags = u16(response, 2);
        if ((flags & 0x0200) != 0) { // TC 截断，走 TCP 重试
            response = tcp(server, request, timeoutMs);
            if (response == null) {
                return new ArrayList<String>();
            }
        }
        return parseAnswers(response, id);
    }

    private static byte[] buildQuery(int id, String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, id);
        writeU16(out, 0x0100); // 标准查询 + 期望递归
        writeU16(out, 1);      // QDCOUNT
        writeU16(out, 0);      // ANCOUNT
        writeU16(out, 0);      // NSCOUNT
        writeU16(out, 0);      // ARCOUNT
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes("US-ASCII");
            out.write(b.length);
            out.write(b);
        }
        out.write(0);
        writeU16(out, TYPE_TXT);
        writeU16(out, CLASS_IN);
        return out.toByteArray();
    }

    private static byte[] udp(String server, byte[] request, int timeoutMs) throws Exception {
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(server);
            socket.send(new DatagramPacket(request, request.length, addr, 53));
            byte[] buf = new byte[1500];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            socket.receive(packet);
            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
            return data;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] tcp(String server, byte[] request, int timeoutMs) throws Exception {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(server, 53), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write((request.length >> 8) & 0xFF);
            out.write(request.length & 0xFF);
            out.write(request);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int len = in.readUnsignedShort();
            byte[] data = new byte[len];
            in.readFully(data);
            return data;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<String> parseAnswers(byte[] buf, int id) {
        List<String> out = new ArrayList<String>();
        if (buf == null || buf.length < 12) {
            return out;
        }
        if (u16(buf, 0) != id) {
            return out;
        }
        int qd = u16(buf, 4);
        int an = u16(buf, 6);
        int off = 12;
        for (int i = 0; i < qd; i++) {
            off = skipName(buf, off);
            off += 4;
            if (off < 0 || off > buf.length) {
                return out;
            }
        }
        for (int i = 0; i < an; i++) {
            off = skipName(buf, off);
            if (off < 0 || off + 10 > buf.length) {
                break;
            }
            int type = u16(buf, off);
            off += 2;
            off += 2;                 // class
            off += 4;                 // ttl
            int rdlen = u16(buf, off);
            off += 2;
            if (off + rdlen > buf.length) {
                break;
            }
            if (type == TYPE_TXT && rdlen > 0) {
                StringBuilder sb = new StringBuilder();
                int p = off;
                int end = off + rdlen;
                while (p < end) {
                    int len = buf[p] & 0xFF;
                    p++;
                    if (p + len > end) {
                        break;
                    }
                    sb.append(new String(buf, p, len, java.nio.charset.Charset.forName("UTF-8")));
                    p += len;
                }
                if (sb.length() > 0) {
                    out.add(sb.toString());
                }
            }
            off += rdlen;
        }
        return out;
    }

    private static int skipName(byte[] buf, int off) {
        while (off >= 0 && off < buf.length) {
            int len = buf[off] & 0xFF;
            if (len == 0) {
                return off + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return off + 2;
            }
            off += len + 1;
        }
        return -1;
    }

    private static int u16(byte[] b, int off) {
        if (off < 0 || off + 2 > b.length) {
            return 0;
        }
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** 供设置页展示：格式化后的候选列表。 */
    public static String describe(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(values.get(i));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
