all: mylib.so server

server: server.c
	gcc -Wall server.c -o server -I../include -L../lib -l dirtree

mylib.o: mylib.c
	gcc -Wall -fPIC -DPIC -c mylib.c

mylib.so: mylib.o
	ld -shared -o mylib.so mylib.o -ldl

clean:
	rm -f *.o *.so
