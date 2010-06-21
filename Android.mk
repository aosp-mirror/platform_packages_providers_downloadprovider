LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)


LOCAL_PACKAGE_NAME := DownloadProvider
LOCAL_CERTIFICATE := media

include $(BUILD_PACKAGE)

# additionally, call tests makefiles
include $(call all-makefiles-under,$(LOCAL_PATH))
