package ai.hermes.runtime.importprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.io.PrintWriter;
import java.io.StringWriter;
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
            if (!"actual_agent_model_turn".equals(data.getString("stage"))
                    || !"AIAgent".equals(data.getString("agent_type"))
                    || !data.getBoolean("model_turn_completed")
                    || !"ANDROID_HERMES_AGENT_TURN_PASS".equals(data.getString("response"))
                    || data.getInt("request_count") != 1
                    || data.getInt("discovery_probe_count") != 1
                    || !"/v1/chat/completions".equals(data.getString("path"))
                    || !"hermes-android-probe".equals(data.getString("model"))
                    || !data.getBoolean("stream")
                    || !data.getBoolean("authorization_verified")
                    || !data.getBoolean("prompt_verified")) {
                throw new AssertionError("Unexpected Hermes Agent turn evidence");
            }
            results.putString("stream", "HERMES_AGENT_MODEL_TURN_PASS " + report);
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            results.putString("stream", "HERMES_AGENT_MODEL_TURN_FAIL " + trace);
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}
