package control;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.*;

/**
 * Created by haitaoyao on 14-2-13.
 */
public class SSHUtils {

    public static final ExecuteResult sshExecute(String user, String host, String command) throws Exception {
        JSch jSch = new JSch();
        final String privateKeyFile = System.getProperty("user.home") + "/.ssh/id_rsa";
        jSch.addIdentity(privateKeyFile);
        Session session = null;
        ChannelExec channel = null;
        try {
            session = jSch.getSession(user, host);
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);

            session.connect();
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            channel.setPty(true);
            channel.connect();
            final OutputStream stdout = new ByteArrayOutputStream();
            final OutputStream stderr = new ByteArrayOutputStream();
            copyStream(channel.getInputStream(), stdout);
            copyStream(channel.getErrStream(), stderr);
            int exitCode = 0;
            if (channel.isClosed()) {
                exitCode = channel.getExitStatus();
            }
            return new ExecuteResult(exitCode, stdout, stderr);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private static final void copyStream(InputStream in, OutputStream out) throws IOException {
        BufferedReader reader = null;
        PrintWriter outPrint = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in));
            outPrint = new PrintWriter(out);
            String line;
            while ((line = reader.readLine()) != null) {
                outPrint.println(line);
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
            if (outPrint != null) {
                outPrint.close();
            }
        }
    }
}
