package ai.hermes.runtime.importprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.JSONObject;

public final class ImportInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try {
            Python.start(new AndroidPlatform(getTargetContext()));
            String report = Python.getInstance().getModule("import_probe")
                .callAttr("run").toString();
            JSONObject data = new JSONObject(report);
            if (!"actual_run_agent_import".equals(data.getString("stage"))
                    || !"AIAgent".equals(data.getString("agent_type"))
                    || data.getBoolean("model_turn_completed")) {
                throw new AssertionError("Unexpected Hermes import evidence");
            }
            results.putString("stream", "HERMES_AGENT_IMPORT_PASS " + report);
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            results.putString("stream", "HERMES_AGENT_IMPORT_FAIL " + error.toString());
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}
