package util;

import java.util.function.Consumer;

public class NodeLinkedList<T> {

    public static class Node<T> {
        public final T value;

        Node<T> next;
        Node<T> prev;

        Node(T value) {
            this.value = value;
        }
    }

    private Node<T> head;

    public NodeLinkedList() {
        head = new Node<>(null);
        head.next = head;
        head.prev = head;
    }

    public Node<T> push(T value) {
        Node<T> node = new Node<>(value);
        Node<T> tail = head.prev;
        node.prev = tail;
        node.next = head;
        head.prev = node;
        tail.next = node;
        return node;
    }

    public boolean isEmpty() {
        return head == head.prev;
    }

    public T getHeadValue() {
        if(isEmpty()) {
            throw new IllegalStateException("Cannot get head of an empty list");
        }
        return head.next.value;
    }

    public boolean isHeadNode(Node<T> node) {
        return head.next == node;
    }

    public Node<T> pull() {
        if(isEmpty()) {
            throw new IllegalStateException("Cannot pull from an empty list");
        }
        Node<T> node = head.next;
        head.next = node.next;
        node.next.prev = head;
        return node;
    }

    public void remove(Node<T> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    public int size() {
        Node<T> temp = head.next;
        int size = 0;
        for(; temp != head; size++, temp = temp.next) ;
        return size;
    }

    public void forEach(Consumer<T> cons) {
        Node<T> temp = head.next;
        for(; temp != head; temp = temp.next) cons.accept(temp.value);
    }
}