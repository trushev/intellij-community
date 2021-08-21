class Test {

  static void m(Test t, Test... ts) {}

  interface I {
    void m(Test... t);
  }

  static void test() {
    I i = Test::<error descr="Incompatible types: Test[] is not convertible to Test">m</error>;
  }
}
