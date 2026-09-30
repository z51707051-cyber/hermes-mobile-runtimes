package ai.hermes.runtime.importprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import ai.hermes.mobile.runtime.bridge.runtime.HermesAndroidToolBridge;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.json.JSONObject;

public final class ImportInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle results = new Bundle();
        try (HermesAndroidToolBridge bridge = HermesAndroidToolBridge.createForUserTask()) {
            Python.start(new AndroidPlatform(getTargetContext()));
            String report = Python.getInstance().getModule("import_probe")
                .callAttr("run", bridge).toString();
            JSONObject data = new JSONObject(report);
            if (!"actual_agent_android_tool_route".equals(data.getString("stage"))
                    || !"AIAgent".equals(data.getString("agent_type"))
                    || !data.getBoolean("model_turn_completed")
                    || !"ANDROID_HERMES_TOOL_ROUTE_PASS".equals(data.getString("response"))
                    || data.getInt("request_count") != 2
                    || data.getInt("discovery_probe_count") != 1
                    || !data.getBoolean("tool_schema_verified")
                    || !data.getBoolean("tool_result_verified")
                    || !data.getBoolean("authorization_verified")) {
                throw new AssertionError("Unexpected Hermes Agent Android tool evidence");
            }
            results.putString("stream", "HERMES_AGENT_ANDROID_TOOL_ROUTE_PASS " + report);
            finish(Activity.RESULT_OK, results);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            results.putString("stream", "HERMES_AGENT_ANDROID_TOOL_ROUTE_FAIL " + trace);
            finish(Activity.RESULT_CANCELED, results);
        }
    }
}
