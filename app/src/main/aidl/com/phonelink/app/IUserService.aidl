package com.phonelink.app;

import android.os.ParcelFileDescriptor;

interface IUserService {
    void destroy() = 16777114;
    void exit() = 1;
    String list(String path) = 2;
    String stat(String path) = 3;
    ParcelFileDescriptor openRead(String path) = 4;
    ParcelFileDescriptor openWrite(String path) = 5;
}
