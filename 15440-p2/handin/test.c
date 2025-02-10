#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>

int main () {
    // how does opening a directory work in C?
    int fd = open("foo_write", O_RDWR | O_CREAT, 0664);
    int bytes_written = write(fd, "hello world!", 100);
    lseek(fd, 0);
    close(fd);
    // printf("bytes written: [%d]\n", bytes_written);
}