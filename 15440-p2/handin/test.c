#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>

int main () {
    int fd, fd2, fd3, fd4, fd5, fd6, fd7, fd8, fd9;
    char buf[100], buf2[100], buf3[100];

    // fd = open("subdir/subdir/../subdir/../../subdir/./subdir/huge_file", O_RDWR|O_CREAT, 0664);
    // close(fd);
    fd2 = open("subdir/subdir/../subdir/../../subdir/./subdir/huge_file", O_RDONLY, 0);
    // fd3 = open("A", O_RDONLY, 0);
    // fd4 = open("A", O_RDWR, 0);
    // write(fd4, "jihgfedcba", 10);
    // close(fd4);
    // read(fd, buf, 5);
    // close(fd);
    // fd2 = open("A", O_RDONLY, 0);
    // read(fd2, buf2, 7);
    close(fd2);
    // read(fd3, buf3, 10);
    // close(fd3);
    printf("fd: buf read [%s]\n", buf);
    printf("fd: buf2 read [%s]\n", buf2);
    // printf("fd: buf3 read [%s]\n", buf3);
}