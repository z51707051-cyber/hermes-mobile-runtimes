package ai.hermes.runtime.probe;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView status = new TextView(this);
        status.setPadding(32, 64, 32, 32);
        status.setText("正在检查 APK 内 Python…\n此探针尚未运行 Hermes Agent。");
        setContentView(status);
        new Thread(() -> {
            String result;
            try {
                synchronized (Python.class) {
                    if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                }
                result = Python.getInstance().getModule("runtime_probe")
                    .callAttr("run", getCacheDir().getAbsolutePath()).toString();
            } catch (Exception error) {
                result = "启动失败：" + error.getClass().getSimpleName();
            }
            final String report = result;
            runOnUiThread(() -> status.setText("仅验证内嵌 Python，尚未运行 Hermes Agent。\n\n" + report));
        }, "python-probe").start();
    }
}
