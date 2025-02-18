#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>

int main () {
    int fd = open("foo", O_RDWR | O_CREAT, 0664);
    int w1 = write(fd, "change in text!", 15);
    int fd2 = open("foo", O_RDONLY, 0);
    char buf[100];
    int r1 = read(fd2, buf, 10);
    close(fd2);
    close(fd);
    int fd3 = open("foo", O_RDONLY, 0);
    char buf2[100];
    int r2 = read(fd3, buf2, 10);
    close(fd3);
    printf("read: bytes read [%d] into buf [%s]\n", r1, buf);
    printf("read: bytes read [%d] into buf [%s]\n", r2, buf2);
}