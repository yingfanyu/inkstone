package com.inkstone.app;

import java.lang.reflect.Method;
import java.util.List;

/** 桌面侧验证：只跑纯逻辑，不碰 Android API。 */
public class Verify {
    public static void main(String[] args) throws Exception {
        List<String> txt = DnsTxt.lookup("note2.929295.xyz", 4000);
        System.out.println("TXT 查询结果: " + DnsTxt.describe(txt) + "  (条数 " + txt.size() + ")");

        Method parsePort = Endpoint.class.getDeclaredMethod("parsePort", String.class);
        Method parseHost = Endpoint.class.getDeclaredMethod("parseHost", String.class);
        parsePort.setAccessible(true);
        parseHost.setAccessible(true);

        String[][] cases = {
                {"9262", "纯端口"},
                {"\"9262\"", "带引号"},
                {"note.1.929295.xyz:9262", "域名:端口"},
                {"[2409:8a28:6020:ca21::61d]:9262", "IPv6:端口"},
                {"https://note.1.929295.xyz:9262/", "完整 URL"},
                {"port=9262;host=note.1.929295.xyz", "键值对"},
                {"abc", "非法"},
                {"70000", "越界端口"},
        };
        for (String[] c : cases) {
            int[] r = (int[]) parsePort.invoke(null, c[0]);
            String h = (String) parseHost.invoke(null, c[0]);
            System.out.printf("%-38s %-10s -> port=%-5d host=%s%n", c[0], c[1], r[0], h == null ? "-" : h);
        }
    }
}
