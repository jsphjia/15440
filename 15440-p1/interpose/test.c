#include <stdio.h>
#include <fcntl.h>
#include <err.h>
#include <errno.h>
#include <string.h>
#include <unistd.h>
#include <stdlib.h>

#define SIZE 1000000

int main () {
    int fd1 = open("input.txt", O_RDONLY);
    int fd2 = open("output.txt", O_WRONLY | O_CREAT | O_TRUNC, 0644);

    char *data = malloc(SIZE);
    read(fd1, data, SIZE);
    write(fd2, data, SIZE);
    write(fd2, data, SIZE);
    write(fd2, data, SIZE);
    write(fd2, data, SIZE);
    close(fd1);
    close(fd2);
    return 0;
}