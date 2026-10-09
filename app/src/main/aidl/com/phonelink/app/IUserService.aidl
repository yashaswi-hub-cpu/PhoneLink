package com.phonelink.app;

import android.os.ParcelFileDescriptor;

interface IUserService {
    void destroy();
    void exit();
    String list(String path);
    String stat(String path);
    ParcelFileDescriptor openRead(String path);
    ParcelFileDescriptor openWrite(String path);
}
