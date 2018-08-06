LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_INSTRUMENTATION_FOR := DownloadProvider
LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base android.test.mock
LOCAL_STATIC_JAVA_LIBRARIES := \
    mockwebserver \
    mockito-target
LOCAL_PACKAGE_NAME := DownloadProviderTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_CERTIFICATE := media

include $(BUILD_PACKAGE)

# additionally, build sub-tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
