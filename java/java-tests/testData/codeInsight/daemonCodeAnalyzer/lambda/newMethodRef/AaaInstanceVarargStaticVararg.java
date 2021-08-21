class Test {
  static class A {
    void foo(A... as) {int i = 1;}
    static void foo(A a, A... as) {double j = 2;}
  }

  interface I {
    void bar(A a);
  }

  static void test() {
    I i = A::<error descr="Reference to 'foo' is ambiguous, both 'foo(A, A...)' and 'foo(A...)' match">foo</error>;
  }
}
