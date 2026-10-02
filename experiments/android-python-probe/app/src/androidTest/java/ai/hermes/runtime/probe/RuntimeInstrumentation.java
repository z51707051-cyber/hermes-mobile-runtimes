package ai.hermes.runtime.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONObject;

public final class RuntimeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            if (!Python.isStarted()) Python.start(new AndroidPlatform(getTargetContext()));
            String report = Python.getInstance().getModule("runtime_probe")
                .callAttr("run", getTargetContext().getCacheDir().getAbsolutePath()).toString();
            JSONObject data = new JSONObject(report);
            if (!data.getBoolean("sqlite_round_trip") || !data.getBoolean("tls_verification")
                    || data.getBoolean("hermes_agent_started")
                    || !data.getString("python").startsWith("3.11.")) {
                throw new AssertionError("Unexpected runtime evidence");
            }
            results.putString("stream", "HERMES_PYTHON_PROBE_PASS " + report);
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", "HERMES_PYTHON_PROBE_FAIL " + error.toString());
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}
