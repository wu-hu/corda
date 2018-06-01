# Deterministic JVM

The code in this module is not yet integrated with the rest of the platform and
stands alone. It will eventually become a part of the node and enforce
deterministic and secure execution of smart contract code, which is mobile and
may propagate around the network without human intervention. Note that this
sandbox is not designed as a anti-DoS mitigation.

To learn more about the design please consult the Corda technical white paper.

**NOTE**: The deterministic JVM has yet to go through thorough review and
testing. It should not be used or relied upon in any production setting until
this warning is removed.

## Getting Started

Open your terminal and navigate to the `experimental/sandbox` folder. Then
issue the following command:

```bash
experimental/sandbox > ./shell/install
```

This will build the DJVM tool and install a shortcut on Bash-enabled systems. It will also generate a Bash completion file and store it in the `shell` folder. This file can be sourced from your Bash initialisation script.

```bash
experimental/sandbox > cd ~
~ > djvm
```

Now, you can create a new Java file from a skeleton that `djvm` provides, compile the file, and consequently run it by issuing the following commands:

```bash
~ > djvm new Hello
~ > vim tmp/net/corda/sandbox/Hello.java
~ > djvm build Hello
~ > djvm run Hello
```

This run will produce some output similar to this:

```
Running class net.corda.sandbox.Hello...
Execution successful
 - result = null

Runtime Cost Summary:
 - allocations = 0
 - invocations = 1
 - jumps = 0
 - throws = 0
```

The output should be pretty self-explanatory, but just to summarise:

* It prints out the return value from the `SandboxedRunnable<Object, Object>.run() ` method implemented in `net.corda.sandbox.Hello`.
* It also prints out the aggregated costs for allocations, invocations, jumps and throws.

---

**TODO**

From the technical white paper

It is important that all nodes that process a transaction always agree on
whether it is valid or not. Because transaction types are defined using JVM
bytecode, this means the execution of that bytecode must be fully
deterministic. Out of the box a standard JVM is not fully deterministic, thus
we must make some modifications in order to satisfy our requirements.
Non-determinism could come from the following sources:

 - Sources of external input e.g. the file system, network, system properties,
   clocks.

 - Random number generators.

 - Different decisions about when to terminate long running programs.

 - Object.hashCode(), which is typically implemented either by returning a
   pointer address or by assigning the object a random number. This can surface
   as different iteration orders over hash maps and hash sets.

 - Differences in hardware floating point arithmetic.

 - Multi-threading.

 - Differences in API implementations between nodes.

 - Garbage collector callbacks.

To ensure that the contract verify function is fully pure even in the face of
infinite loops we construct a new type of JVM sandbox. It utilises a bytecode
static analysis and rewriting pass, along with a small JVM patch that allows
the sandbox to control the behaviour of hashcode generation. Contract code is
rewritten the first time it needs to be executed and then stored for future
use.

The bytecode analysis and rewrite performs the following tasks:

 - Inserts calls to an accounting object before expensive bytecodes. The goal
   of this rewrite is to deterministically terminate code that has run for an
   unacceptably long amount of time or used an unacceptable amount of memory.
   Expensive bytecodes include method invocation, allocation, backwards jumps
   and throwing exceptions.

 - Prevents exception handlers from catching Throwable, Error or ThreadDeath.

 - Adjusts constant pool references to relink the code against a ‘shadow’ JDK,
   which duplicates a subset of the regular JDK but inside a dedicated sandbox
   package. The shadow JDK is missing functionality that contract code
   shouldn’t have access to, such as file IO or external entropy.

 - Sets the strictfp flag on all methods, which requires the JVM to do floating
   point arithmetic in a hardware independent fashion. Whilst we anticipate
   that floating point arithmetic is unlikely to feature in most smart
   contracts (big integer and big decimal libraries are available), it is
   available for those who want to use it.

 - Forbids invokedynamic bytecode except in special cases, as the libraries
   that support this functionality have historically had security problems and
   it is primarily needed only by scripting languages. Support for the specific
   lambda and string concatenation metafactories used by Java code itself are
   allowed.

 - Forbids native methods.

 - Forbids finalizers.

The cost instrumentation strategy used is a simple one: just counting bytecodes
that are known to be expensive to execute. Method size is limited and jumps
count towards the budget, so such a strategy is guaranteed to eventually
terminate. However it is still possible to construct bytecode sequences by hand
that take excessive amounts of time to execute. The cost instrumentation is
designed to ensure that infinite loops are terminated and that if the cost of
verifying a transaction becomes unexpectedly large (e.g. contains algorithms
with complexity exponential in transaction size) that all nodes agree precisely
on when to quit. It is not intended as a protection against denial of service
attacks. If a node is sending you transactions that appear designed to simply
waste your CPU time then simply blocking that node is sufficient to solve the
problem, given the lack of global broadcast.

Opcode budgets are separate per opcode type, so there is no unified cost model.
Additionally the instrumentation is high overhead. A more sophisticated design
would be to statically calculate bytecode costs as much as possible ahead of
time, by instrumenting only the entry point of ‘accounting blocks’, i.e. runs
of basic blocks that end with either a method return or a backwards jump.
Because only an abstract cost matters (this is not a profiler tool) and because
the limits are expected to bet set relatively high, there is no need to
instrument every basic block. Using the max of both sides of a branch is
sufficient when neither branch target contains a backwards jump. This sort of
design will be investigated if the per category opcode-at-a-time accounting
turns out to be insufficient.

A further complexity comes from the need to constrain memory usage. The sandbox
imposes a quota on bytes allocated rather than bytes retained in order to
simplify the implementation. This strategy is unnecessarily harsh on smart
contracts that churn large quantities of garbage yet have relatively small peak
heap sizes and, again, it may be that in practice a more sophisticated strategy
that integrates with the garbage collector is required in order to set quotas
to a usefully generic level.

Control over Object.hashCode() takes the form of new JNI calls that allow the
JVM’s thread local random number generator to be reseeded before execution
begins. The seed is derived from the hash of the transaction being verified.

Finally, it is important to note that not just smart contract code is
instrumented, but all code that it can transitively reach. In particular this
means that the ‘shadow JDK’ is also instrumented and stored on disk ahead of
time.
