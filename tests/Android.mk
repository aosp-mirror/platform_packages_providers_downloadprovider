LOCAL_PATH:= $(call my-dir)

########################

include $(CLEAR_VARS)

# no tests to build for now

# additionally, build sub-tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))