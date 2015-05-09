#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/ioctl.h>
#include <sys/uio.h>
#include <sys/un.h>

#include <stdlib.h> // exit
#include <stdio.h> // printf

#include <android/log.h>

#define LOG_TAG "fdhelper"

void DieWithError(const char *errorMessage)  /* Error handling function */
{
    const char* errDesc = strerror(errno);
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s errno %s(%d)", errorMessage, errDesc, errno);
    fprintf(stderr, "Error: %s - %s\n", errorMessage, errDesc);
    exit(errno);
}

int ancil_send_fds_with_buffer(int sock, int fd, const char *buf)
{
    struct msghdr msghdr;
    msghdr.msg_name = NULL;
    msghdr.msg_namelen = 0;
    msghdr.msg_flags = 0;

    struct iovec iovec;
    iovec.iov_base = (void*) buf;
    iovec.iov_len = sizeof(buf);

    msghdr.msg_iov = &iovec;
    msghdr.msg_iovlen = 1;

    union {
        struct cmsghdr  cmsghdr;
        char        control[CMSG_SPACE(sizeof (int))];
    } cmsgfds;
    msghdr.msg_control = cmsgfds.control;
    msghdr.msg_controllen = sizeof(cmsgfds.control);

    struct cmsghdr  *cmsg;
    cmsg = CMSG_FIRSTHDR(&msghdr);
    cmsg->cmsg_len = CMSG_LEN(sizeof (int));
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    *((int *) CMSG_DATA(cmsg)) = fd;

    return (sendmsg(sock, &msghdr, 0) >= 0 ? 0 : -1);
}

// Fork and get ourselves a tty. Acquired tty will be new stdin,
// Standard output streams will be redirected to new_stdouterr.
// Returns control side tty file descriptor.
int GetTTY(int new_stdouterr) {
    int masterFd = open("/dev/ptmx", O_RDWR);
    if (masterFd < 0)
        DieWithError("failed to open /dev/ptmx");

    char devname[64];
    pid_t pid;

    if(unlockpt(masterFd)) // grantpt is unnecessary, because we already assume devpts by using /dev/ptmx
        DieWithError("trouble with /dev/ptmx");

    memset(devname, 0, sizeof(devname));

    // Early (Android 1.6) bionic versions of ptsname_r had a bug where they returned the buffer
    // instead of 0 on success.  A compatible way of telling whether ptsname_r
    // succeeded is to zero out errno and check it after the call
    errno = 0;
    int ptsResult = ptsname_r(masterFd, devname, sizeof(devname));
    if (ptsResult && errno)
        DieWithError("ptsname_r() returned error");

    pid = fork();
    if(pid < 0)
        DieWithError("fork() failed");

    if (pid) {
        // tell the parent a PID of forked process
        printf("PID:%d", pid);
        exit(0);
    } else {
        int pts;

        setsid();

        pts = open(devname, O_RDWR);
        if(pts < 0)
            exit(-1);

        ioctl(pts, TIOCSCTTY, 0);

        dup2(pts, 0);
        dup2(new_stdouterr, 1);
        dup2(new_stdouterr, 2);
    }

    return masterFd;
}

// Perform initial greeting dance with server over socket with supplied name.
// The procedure ends with "READY" and tty file descriptor are sent over the socket
// and "GO" being received in response, which means, that the server process has acquired
// file descriptor on the controlling terminal
int Bootstrap(char *socket_name) {
    int sock;

    if ((sock = socket(PF_LOCAL, SOCK_STREAM, 0)) < 0)
            DieWithError("socket() failed");

    struct sockaddr_un echoServAddr;

    memset(&echoServAddr, 0, sizeof(echoServAddr));

    echoServAddr.sun_family = AF_LOCAL;

    strncpy(echoServAddr.sun_path + 1, socket_name, sizeof(echoServAddr.sun_path) - 2);

    int size = sizeof(echoServAddr) - sizeof(echoServAddr.sun_path) + strlen(echoServAddr.sun_path+1) + 1;

    if (connect(sock, (struct sockaddr *) &echoServAddr, size) < 0)
        DieWithError("connect() failed");

    int tty = GetTTY(sock);

    if (ancil_send_fds_with_buffer(sock, tty, "READY"))
        DieWithError("sending tty descriptor failed");

    if (scanf("GO") == 2) {
        close(tty);
    } else
        DieWithError("incomplete confirmation message");

    return sock;
}

int main(int argc, char *argv[]) {
    // connect to supplied address and send the greeting message to server
    int sock = Bootstrap(argv[1]);

    // process requests infinitely (we will be killed when done)
    int* nameLength;
    char status[3];

    while(1) {
        int nameLength;

        if (scanf("%d", &nameLength) != 1)
            DieWithError("reading a filename length failed");

        char furtherFormat[20];

        sprintf(furtherFormat, "%ds,%%s", nameLength);

        char* filename;
        if ((filename = (char*) calloc(nameLength + 1, 1)) == NULL)
            DieWithError("calloc() failed");

        char mode[4];
        if (scanf(furtherFormat, filename, mode) != 1)
            DieWithError("reading a filename/mode failed");

        int targetFd = open(filename, 0);

        if (targetFd > 0) {
            if (ancil_send_fds_with_buffer(sock, targetFd, "DONE"))
                DieWithError("sending file descriptor failed");
        } else {
            fprintf(stderr, "Error: failed to open a file - %s\n", strerror(errno));
        }

        free(filename);
        close(targetFd);
    }

    return -1;
}