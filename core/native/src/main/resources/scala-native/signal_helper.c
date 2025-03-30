#include <stddef.h>
#include <signal.h>

typedef void (*Handler)(int);

int install_handler(int signum, Handler handler) {
    int error;
    struct sigaction action;
    action.sa_handler = handler;
    action.sa_flags = 0;
    error = sigemptyset(&action.sa_mask);
    if (error != 0) {
        return error;
    }
    error = sigaddset(&action.sa_mask, 13); // mask SIGPIPE
    if (error != 0) {
        return error;
    }
    return sigaction(signum, &action, NULL);
}
