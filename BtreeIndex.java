/**
 * This program demonstrates how to do binary I/O in Java.  The idea is to read
 * in a block and work with it as bytes, and write out a block of bytes.  That
 * way you're not constrained to working only with text files.
 *
 * Written by Sankalpa Rath at The University of Texas at Dallas
 **/

import java.io.FileReader;



import java.io.IOException;
import java.io.RandomAccessFile;

public class BtreeIndex{
    private int noOfNodes = 0; //total number of nodes/blocks currently in the tree
    private int M;
    private Node root;       // root of the B-tree
    private int height;      // height of the B-tree
    private RandomAccessFile output;
    private int keySize;
    private RandomAccessFile input;

    // helper B-tree entry class
    // internal node entries would only use key, next
    // external node entries would only use key, val
    private class Entry {
        private String key;    // key 
        private long val;      // val i.e. offset in the txt file for the key
        private long next;     // block address to the next node in the tree
        public Entry(String key, long val, long next) {
            this.key  = key;
            this.val  = val;
            this.next = next;
        }
        // get the next Node if it exists
        Node getNext() throws IOException {
            if(next > 0) {
                return new Node(next);
            }
            return null;
        }
    }
    
    // helper B-tree node data type
    private class Node {      
        private int noOfChildren;   // number of children
        private Entry[] children = new Entry[M];   // the array of children
        boolean internal;  // flag to check if the node is internal
        long offset; // location of the node in the index file (block address)
        
        /** Create a new node with given number of children**/
        private Node(int k){
            noOfChildren = k;
            noOfNodes++; // incrementing the total number of nodes
            offset = noOfNodes * 1024; // setting the new node's block address to the next available block
        }
        /** Create a node object with the given number of children and the 
         * block address as provided. Used when updating the root node**/
        private Node(int k, long offset){
            noOfChildren = k;
            this.offset = offset;
        }
        /** Create a node object reading the block at the given block address 
         * in the index file**/
        private Node(long offset) throws IOException {
        	/** Node byte format is 
        	 * 1st byte - internal node flag 
        	 * 2-5 bytes - integer number of keys in the node. 
        	 * 6-1024 bytes - sequential data key1, pointer1, key2, pointer2....**/
            int i;
            this.offset = offset;
            output.seek(offset);
            internal = output.readBoolean();
            noOfChildren = output.readInt();
            for( i = 0 ; i < noOfChildren; i++){
                byte[] b = new byte[keySize];
                output.read(b, 0, keySize);
                // Populate the children according to the flag.
                children[i] = internal ? new Entry(new String(b), -1, output.readLong()) : new Entry(new String(b), output.readLong(), -1);
            }
        }
        
        //Counter part to reading a node from a block addreess. This function write a node to its corresponding to persist.
        private void writeToFile() throws IOException {
            int i;
            output.seek(offset);
            output.writeBoolean(internal);
            output.writeInt(noOfChildren);
            for(i = 0; i < noOfChildren; i++){
                output.writeBytes(children[i].key);
                output.writeLong(internal ? children[i].next : children[i].val);
            }
        }
    }
    /**
     * Initializes an empty B-tree. When creating a nex index gile
     */
    public BtreeIndex(String textFileName, int keySize, String outputFile) throws Exception {
        M = 1019 / (keySize + 8);
        output = new RandomAccessFile(outputFile, "rw");
        // writing meta data to the first block. 
        // text file name - 0-255 bytes
        // keysize - 256-259 bytes
        // height of B-Tree - 260 - 263 bytes
        output.seek(noOfNodes);
        output.writeBytes(textFileName);
        output.seek(256L);
        output.writeInt(keySize);
        output.seek(1024);
        this.keySize = keySize;
        root = new Node(0);
        input = new RandomAccessFile(textFileName, "rw");
        height = 0;
    }
    
    // Create a BTreeIndex object reading an existing index file
    public BtreeIndex(String indexFile) throws Exception {
        output = new RandomAccessFile(indexFile, "rw");
        output.seek(0);
        long l = output.length();
        noOfNodes = (int) Math.ceil( ((double) l) / 1024.0) - 1; // -1 because of the meta data block
        byte[] b = new byte[256];
        output.read(b, 0, 256);
        String s = new String(b);  // text file
        keySize = output.readInt(); // key size
        M = 1019 / (keySize + 8);
        output.seek(260L);
        height = output.readInt(); // read height
        input = new RandomAccessFile(s.trim(), "rw");
        root = new Node(1024L); //initialize the root node which is always at the block address 1024
    }
    
    /**
     * Inserts the key-value pair into the symbol table.
     */
    public void put(String key, long val) throws IOException {
        if (key == null) throw new IllegalArgumentException("argument key to put() is null");
        Node newNode = insert(root, key, val, height);
        if (newNode == null) return;
        // need to split root
        Node t = new Node(2, 1024);
        // copy the old root to a new block
        noOfNodes++;
        root.offset = noOfNodes * 1024;
        root.writeToFile();
        t.children[0] = new Entry(root.children[0].key, -1, root.offset);
        t.children[1] = new Entry(newNode.children[0].key, -1, newNode.offset);
        t.internal = true;
        root = t;
        // update the root value to the new one at 1024
        root.writeToFile();
        height++;
        output.seek(260L);
        output.writeInt(height); // Update height
    }
    
    private Node insert(Node h, String key, long val, int ht) throws IOException {
        int j;
        Entry t = new Entry(key, val, -1);
        // external node
        if (ht == 0) {
            h.internal = false;
            for (j = 0; j < h.noOfChildren; j++) {
                if (less(key, h.children[j].key))
                    break; // we found the position to place the new key
            }
        }
        // internal node
        else {
            h.internal = true;
            for (j = 0; j < h.noOfChildren; j++) {
                if ((j+1 == h.noOfChildren) || less(key, h.children[j+1].key)) {
                    Node u = insert(h.children[j++].getNext(), key, val, ht-1);
                    if (u == null)
                        return null;
                    t.key = u.children[0].key;
                    t.next = u.offset;
                    break;
                }
            }
        }
        /*this below loop is executed when key is less than any previously
         stored keys*/
        for (int i = h.noOfChildren; i > j; i--)
            h.children[i] = h.children[i-1];
        h.children[j] = t;
        h.noOfChildren++;
        if (h.noOfChildren < M) {
            h.writeToFile(); // write the updated node to file
            return null;
        }
        else
            return split(h);
    }
    
    // split node in half
    private Node split(Node h) throws IOException {
        Node t = new Node(M/2);
        t.internal = h.internal;
        h.noOfChildren = M/2;
        for (int j = 0; j < M/2; j++)
            t.children[j] = h.children[M/2+j];
        h.writeToFile(); // write both updated nodes to file
        t.writeToFile();
        return t;
    }


    /**
     * Returns the value associated with the given key.
     */
    public long get(String key) throws Exception {
        return search(root, key, height);
    }
    private long search(Node x, String key, int ht) throws IOException {
        // external node
        if (ht == 0) {
            for (int j = 0; j < x.noOfChildren; j++) {
                if (eq(key, x.children[j].key))
                    return x.children[j].val;
            }
        }
        // internal node
        else {
            for (int j = 0; j < x.noOfChildren; j++) {
                if (j+1 == x.noOfChildren || less(key, x.children[j+1].key))
                    return search(x.children[j].getNext(), key, ht-1);
            }
        }
        return -1; // return -1 if the key is not found
    }
    // comparison functions - make Comparable instead of Key to avoid casts
    private boolean less(Comparable k1, Comparable k2) {
        return k1.compareTo(k2) < 0;
    }

    private boolean eq(Comparable k1, Comparable k2) {
        return k1.compareTo(k2) == 0;
    }
    
    public String getDataAtOffset(long offset) throws IOException {
        input.seek(offset);
        return input.readLine();
    }

    private int list(Node x, String key, int i, int ht) throws IOException {
    	if(i==0) // terminate recurtion if i == 0
    		return 0;
        // external node
        int j;
        if (ht == 0) {
            for (j = 0; j < x.noOfChildren; j++) {
                if (!less(x.children[j].key, key))
                    break;
            }
        }

        // internal node
        else {
            for (j = 0; j < x.noOfChildren; j++) {
                if (j+1 == x.noOfChildren || less(key, x.children[j+1].key)){
                    i = list(x.children[j].getNext(), key, i, ht-1);
                    if(i==0)
                    	break;
                }
            }
        }

        while(i > 0 && j < x.noOfChildren) {
            System.out.println(getDataAtOffset(x.children[j].val));
            i--;
            j++;
        }
        return i;
    }
    private void list(String arg, int i) throws IOException {
        list(root,arg, i, height);
    }

    public void insertNewRecord(String s) throws Exception {
        String newKey = s.substring(0, keySize);
        long a = get(newKey); 
        if( a > 0)
        {
            System.out.println("Key already exists at offeset: " + a + "!");
            return;
        }
//       System.out.println(p);
        long offset = input.length();
        input.seek(offset);
         // assuming that the file has a trailing new line charecter!
        input.writeBytes(s.endsWith("\n") ? s : s + "\n"); // add new line at the end of the string if the given string is not terminated properly
        put(newKey, offset);
        System.out.println("Placed new record at offset: " + offset);
    }

    public static void main(String[] args) throws Exception  {
        switch (args[0]){
            case "-create":
                RandomAccessFile rr = new RandomAccessFile(args[1], "r");
                String rl;
                long offset = 0;
                int keySize = Integer.parseInt(args[3]);
                BtreeIndex btreeIndex = new BtreeIndex(args[1], keySize, args[2]);
                while ((rl = rr.readLine()) != null) {
                    String h1 = rl.substring(0, keySize);
                    String h2 = rl.substring(keySize, rl.length());
                    long p = btreeIndex.get(h1);
                    if(p < 0) // check if the key is not present
                        btreeIndex.put(h1, offset);
                    else
                    	System.out.println("Tried to insert entry with key: " + h1 +". Failed as key was already present at offest: " + p);
                    offset = rr.getFilePointer();
                }
                rr.close();
                btreeIndex.input.close();
                btreeIndex.output.close();
                break;
            case "-find":
                BtreeIndex btreeIndex1 = new BtreeIndex(args[1]);
                long offset2 = btreeIndex1.get(args[2]);
                if(offset2 > 0)
                    System.out.println("Found key at offset: "+ offset2 +" with value: " + btreeIndex1.getDataAtOffset(offset2));
                else
                    System.out.println("Key not found!");
                btreeIndex1.input.close();
                btreeIndex1.output.close();
                break;
            case "-insert":
                BtreeIndex btreeIndex2 = new BtreeIndex(args[1]);
                btreeIndex2.insertNewRecord(args[2]);
                btreeIndex2.input.close();
                btreeIndex2.output.close();
                break;
            case "-list":
                BtreeIndex btreeIndex3 = new BtreeIndex(args[1]);
                btreeIndex3.list(args[2],Integer.parseInt(args[3]));
                btreeIndex3.output.close();
                btreeIndex3.input.close();
                break;
        }

    }

}


