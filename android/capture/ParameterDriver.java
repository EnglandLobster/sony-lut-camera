package io.github.englandlobster.lutcamera;

import java.util.Map;

/** Flattened Camera.Parameters access. Sony CameraEx extends the normal map
 * with exposure, focus and capture-format keys; callers verify by readback. */
interface ParameterDriver {
    Map<String,String> read() throws Exception;
    void write(Map<String,String> patch) throws Exception;
}
