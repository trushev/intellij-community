class Test {

  static class A {
    static void foo(A a, A... as) {}
  }

  interface I {
    void bar(A... as);
  }

  static void test() {
    I i = A::<error descr="Incompatible types: A[] is not convertible to A">foo</error>;
  }
}
