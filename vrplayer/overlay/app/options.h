// Based on PICO OpenXR VideoPlayer Demo options.h (Apache-2.0)
#pragma once

struct Options {
    std::string GraphicsPlugin{"OpenGLES"};
    std::string FormFactor{"Hmd"};
    std::string ViewConfiguration{"Stereo"};
    std::string EnvironmentBlendMode{"Opaque"};
    std::string AppSpace{"Local"};
    std::string VideoMode{"360"};
    std::string VideoFileName{"http://127.0.0.1:8877/video"};

    struct {
        XrFormFactor FormFactor{XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY};
        XrViewConfigurationType ViewConfigType{XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO};
        XrEnvironmentBlendMode EnvironmentBlendMode{XR_ENVIRONMENT_BLEND_MODE_OPAQUE};
    } Parsed;
};
