class Test {
  static class A {
    void foo() {int i = 1;}
    void foo(A a) {double j = 2;}
  }

  interface I {
    void bar(A a);
  }

  static void test() {
    I i = A::foo;
  }
}
