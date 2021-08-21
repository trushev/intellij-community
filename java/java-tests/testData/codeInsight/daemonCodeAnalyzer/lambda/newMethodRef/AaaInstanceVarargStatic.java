class Test {
  static class A {
    void foo(A... as) {int i = 1;}
    static void foo(A a) {double j = 2;}
  }

  interface I {
    void bar(A a);
  }

  static void test() {
    I i = A::<error descr="Reference to 'foo' is ambiguous, both 'foo(A)' and 'foo(A...)' match">foo</error>;
  }
}
