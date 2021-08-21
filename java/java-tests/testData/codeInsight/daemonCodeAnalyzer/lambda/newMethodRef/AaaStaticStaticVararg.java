class Test {
  static class A {
    static void foo(A a) {int i = 1;}
    static void foo(A a, A... as) {double j = 2;}
  }

  interface I {
    void bar(A a);
  }

  static void test() {
    I i = A::foo;
  }
}
