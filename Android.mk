LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := DownloadProvider
LOCAL_CERTIFICATE := media
LOCAL_PRIVILEGED_MODULE := true
LOCAL_STATIC_JAVA_LIBRARIES := guava \
    android-support-documents-archive

LOCAL_JACK_COVERAGE_INCLUDE_FILTER := com.android.providers.downloads.*

include $(BUILD_PACKAGE)

# build UI + tests
include $(call all-makefiles-under,$(LOCAL_PATH))
