package control;

import java.io.OutputStream;

/**
* Created by haitaoyao on 14-2-13.
*/
public class ExecuteResult {

    public final int status;

    public int getStatus() {
        return status;
    }

    public OutputStream getStdout() {
        return stdout;
    }

    public OutputStream getStderr() {
        return stderr;
    }

    private final OutputStream stdout;
    private final OutputStream stderr;

    public ExecuteResult(int status, OutputStream stdout, OutputStream stderr){
        this.status = status;
        this.stdout = stdout;
        this.stderr = stderr;
    }
}
