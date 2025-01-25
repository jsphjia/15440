all: mylib.so server

server: server.o
	gcc -Wall -Werror server.o -o server -L../lib -ldirtree

server.o: server.c
	gcc -Wall -Werror -I../include -c server.c 

mylib.o: mylib.c
	gcc -Wall -fPIC -DPIC -c mylib.c

mylib.so: mylib.o
	ld -shared -o mylib.so mylib.o -ldl

clean:
	rm -f *.o *.so
