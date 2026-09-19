// Smoke test for the receiver type inference behind inter-procedural dispatch resolution.
//
// Compile and extract with:
//   javac -d target/ target/VirtualDispatch.java
//   ./gradlew run --args="target/ output/ VirtualDispatch main inter"
//
// Two things should hold in the result:
//   * the assertion in Checker$1.write is listed in metadata.assertionPointIds -- it is only
//     reachable through a call whose declared receiver type (Writer) says nothing about the
//     anonymous override that actually runs;
//   * extraction terminates. The walk() loop defines its receiver in terms of its own call, so a
//     naive trace of the receiver's origin recurses forever.
public class VirtualDispatch {

    interface Writer {
        void write(String s);
    }

    static class Checker {
        Writer writer() {
            return new Writer() {
                @Override
                public void write(String s) {
                    check(s);
                }
            };
        }

        void check(String s) {
            if (s.isEmpty()) {
                assert false;
            }
        }
    }

    static class Node {
        Node next;

        Node getNext() {
            return next;
        }
    }

    static int walk(Node node) {
        int length = 0;
        while (node != null) {
            node = node.getNext();
            length++;
        }
        return length;
    }

    public static void main(String[] args) {
        Writer w = new Checker().writer();
        w.write(args.length > 0 ? args[0] : "");
        walk(new Node());
    }
}
