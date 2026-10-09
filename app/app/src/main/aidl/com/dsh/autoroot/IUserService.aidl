package com.dsh.autoroot;

interface IUserService {

    void destroy() = 16777114; // Destroy method defined by Shizuku server

    void exit() = 1;

    /** Run a shell command AS THE SHELL USER (uid 2000) and return merged output. */
    String run(String cmd) = 2;

    /** Extract our bundled payloads out of our own APK into /data/local/tmp. */
    String extractAll(String apkPath) = 3;

    /** Is root available? */
    boolean isRooted() = 4;

    /** uid of this service process (should be 2000 = shell). */
    int uid() = 5;
}
