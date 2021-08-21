class Test {
  static class A {
    void foo() {int i = 1;}
    static void foo(A a) {double j = 2;}
  }

  interface I {
    void bar(A a);
  }

  static void test() {
    I i = A::<error descr="Reference to 'foo' is ambiguous, both 'foo(A)' and 'foo()' match">foo</error>;
  }
}
