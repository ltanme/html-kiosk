import com.local.htmlkiosk.HttpServer;

import java.io.File;

/** 桌面端跑一遍 HttpServer，验证上传/列表/Range/删除逻辑。 */
public class ServerTest {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        int port = Integer.parseInt(args[1]);
        HttpServer server = new HttpServer(root, port,
                "<!doctype html><html><body><h1>upload page</h1></body></html>");
        server.setListener(new HttpServer.Listener() {
            @Override
            public void onFilesChanged() {
                System.out.println("FILES_CHANGED");
            }
        });
        server.start();
        System.out.println("READY " + port + " " + root.getAbsolutePath());
        Thread.sleep(30 * 60 * 1000L);
    }
}