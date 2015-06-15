LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := fdshare
LOCAL_SRC_FILES := fdhelper.c
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Os

include $(BUILD_EXECUTABLE)