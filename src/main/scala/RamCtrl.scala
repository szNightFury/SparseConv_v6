import spinal.core._
import spinal.lib._

import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps
import scala.reflect.ClassTag


case class RamCtrl(cfg: Config, weightsInit: Array[Array[Array[Array[Int]]]]) extends Component{

  val (fhW,  fwW,  fh,  fw    ) = (cfg.fhW,  cfg.fwW,  cfg.fh,    cfg.fw                 )
  val (khW,  kwW,  kh,  kw    ) = (cfg.khW,  cfg.kwW,  cfg.kh,    cfg.kw                 )
  val (faW,  kaW,  fa,  ka    ) = (cfg.faW,  cfg.kaW,  cfg.fa,    cfg.ka                 )
  val (coW,  baW,  ba,  nb    ) = (cfg.coW,  cfg.baW,  cfg.batch, cfg.nbatch             )
  val (akW1, akW2, avW, re, nr) = (cfg.akW1, cfg.akW2, cfg.avW,   cfg.remain, cfg.nremain)
  val (tsW, piece) = (cfg.tsW, cfg.piece)

  val io = new Bundle {
    val inputs = slave(Stream(Fragment(RamAddrPort(tsW, avW, akW1, akW2))))
    val outputs = master(Stream(ConvPort(tsW, coW, fwW, fhW)))
  }

  noIoPrefix()

  def flatten2one[T1: ClassTag](org: Array[Array[Array[Array[T1]]]]): Array[T1] = {
    org.flatten.flatten.flatten
  }

  def createMem(num: Int, size: Int, width: Int): Array[Mem[Bits]] = {
    Array.fill(num)(Mem(Bits(width bits), for (i <- 0 until size) yield B(0, width bits)).addAttribute("ram_style", "block"))
  }

  def myStreamReadSync[T <: Data, T2 <: LinkDataPort](mem: Mem[T], cmd: Stream[UInt], linkedData: T2, clearFlag: Bool,
                                                      cco: Int, crossClock: Boolean = false): Stream[ReadRet2Linked[Bool, T2, UInt]] = {
    val ret = Stream(new ReadRet2Linked(Bool(), linkedData, UInt(coW bits)))

    val retValid = RegInit(False)
    val retData = mem.readSync(cmd.payload, cmd.ready, clockCrossing = crossClock)
    val retLinked = RegNextWhen(linkedData, cmd.ready)

    when(ret.ready) {
      retValid := Bool(false)
    }
    when(cmd.ready) {
      retValid := cmd.valid
    }

    cmd.ready := ret.isFree

    val threshold = 2048
    val cond = (retData.asBits.asSInt > threshold) || (linkedData.timeStep === cfg.ts - 1)
    clearFlag.setWhen(cond)

    ret.valid := retValid
    ret.value := cond
    ret.linked1 := retLinked
    ret.linked2 := U(cco, coW bits)
    ret
  }

  // ROM and RAM for Kernel and Membrane Potentials
  val vmem = createMem(nb, fa * ba, 18) ++ createMem(nr, fa * re, 18)
  val vmemClearFlag = Vec(Vec.fill(nb)(Vec.fill(fa * ba)(Reg(Bool()))) ++
                          Vec.fill(nr)(Vec.fill(fa * re)(Reg(Bool()))))
  val kernel = weightsInit.grouped(2 * ba).toArray.map(
                 data => Mem(Bits(8 bits),
                   flatten2one(data).map(B(_, 8 bits)).seq).
                     addAttribute("rom_style", "block"))


  val (cx, cy, ca, cb, cs) = (Counter(fw), Counter(fh), Counter(fa), Counter(ba), Counter(2))
  val rca = RegNext(ca.value)

  // Register the valid signals
  val curTsb   = RegNextWhen(io.inputs.curTs, io.inputs.fire)
  val vrAddr1b = RegNextWhen(io.inputs.vmemAddr, io.inputs.fire)

  // Vmem Ram port 1: read and update the membrane voltage
  val vrAddr1f = io.inputs.vmemAddr
  val vrAddr1  = io.inputs.fire.asUInt.mux(0 -> vrAddr1b, 1 -> vrAddr1f)
  // Vmem Ram port 2: traverse all membrane voltage

  // Kernel Ram port 1 & 2: read the kernel weight
  val krAddr1  = io.inputs.kernelAddr1
  val krAddr2  = io.inputs.kernelAddr2

  val vrData1  = Vec(SInt(18 bits), piece)
  val krData1  = Vec(SInt(8 bits), piece)
  val krData2  = Vec(SInt(8 bits), piece)


  // Read valid after input fire or hold until the membrane voltage has been updated
  val vrValid = io.inputs.fire || ((vrAddr1b > rca) && (curTsb.lsb.asUInt =/= cs))
  // Write valid after read (hold for one clock)
  val vwValid = vrValid.fall(False)
  // Input ready after write have been done
  val inputsReady  = RegInit(True).clearWhen(io.inputs.valid).setWhen(vwValid)
  io.inputs.ready := inputsReady

  // Register the address that won't be updated until next time step
  val baseAddr = RegNextWhen(io.inputs.vmemAddr, io.inputs.firstFire)
  // Generate the base offset to index the specific address for traversal
  val startAddr = Vec(for(i <- 0 until ba) yield U(i * fa, avW bits))

  // Generate the stream of cmd to traverse the membrane potential
  val readCmd = Stream(ReadCmdPort(avW, tsW, fwW, fhW))
  val readCmdFork = StreamFork(readCmd, nb)
  readCmd.valid := (ca < baseAddr) || (curTsb.lsb.asUInt =/= cs)
  readCmd.vmemAddr := ca.value + startAddr(cb)
  readCmd.linkData.timeStep := (curTsb.lsb.asUInt === cs).asUInt.mux(0 -> (curTsb - 1), 1 -> curTsb)
  readCmd.linkData.fmapAddr.x := cx
  readCmd.linkData.fmapAddr.y := cy
  when (readCmd.fire) {
    cb.increment()
    when (cb.willOverflowIfInc) {
      ca.increment()
      cx.increment()
      when(cx.willOverflowIfInc) {
        cy.increment()
        when(cy.willOverflowIfInc) {
          cs.increment()
        }
      }
    }
  }

  val vmemOutputType = ReadRet2Linked(Bool(), LinkDataPort(tsW, fwW, fhW), UInt(coW bits))
  val vmemOutput = Array.fill(nb)(Stream(vmemOutputType))


  if (nb % 2 == 0 && nr == 0){
    for (i <- 0 until nb / 2) {
      val updateClearFlag1 = vmemClearFlag(2 * i)(vrAddr1)
      val traverseClearFlag1 = vmemClearFlag(2 * i)(readCmdFork(2 * i).vmemAddr)
      vrData1(2 * i) := vmem(2 * i).readWriteSync(
        address = vrAddr1,
        data = updateClearFlag1.asUInt.mux
               (0 -> (vrData1(2 * i) + krData1(i)).asBits, 1 -> krData1(i).resize(18 bits).asBits),
        enable = vrValid || vwValid,
        write = vwValid
      ).asSInt
      updateClearFlag1.clearWhen(vwValid && updateClearFlag1)
      vmemOutput(2 * i) = myStreamReadSync(vmem(2 * i), readCmdFork(2 * i).translateWith(readCmdFork(2 * i).vmemAddr),
                                            readCmdFork(2 * i).linkData, traverseClearFlag1, 2 * i)

      val updateClearFlag2 = vmemClearFlag(2 * i + 1)(vrAddr1)
      val traverseClearFlag2 = vmemClearFlag(2 * i + 1)(readCmdFork(2 * i + 1).vmemAddr)
      vrData1(2 * i + 1) := vmem(2 * i + 1).readWriteSync(
        address = vrAddr1,
        data = updateClearFlag2.asUInt.mux
               (0 -> (vrData1(2 * i + 1) + krData2(i)).asBits, 1 -> krData2(i).resize(18 bits).asBits),
        enable = io.inputs.fire || vwValid,
        write = vwValid
      ).asSInt
      updateClearFlag2.clearWhen(vwValid && updateClearFlag2)
      vmemOutput(2 * i + 1) = myStreamReadSync(vmem(2 * i + 1), readCmdFork(2 * i + 1).translateWith(readCmdFork(2 * i + 1).vmemAddr),
                                                readCmdFork(2 * i + 1).linkData, traverseClearFlag2, 2 * i + 1)

      krData1(i) := kernel(i).readSync(
        enable  = io.inputs.fire,
        address = krAddr1
      ).asSInt
      krData2(i) := kernel(i).readSync(
        enable  = io.inputs.fire,
        address = krAddr2
      ).asSInt
    }
  }

  val arbitrated = StreamArbiterFactory.sequentialOrder.transactionLock.on(vmemOutput)
  //val b = d.translateWith(ConvPortBundle(d.linked1.timeStep, d.linked2, d.linked1.fmapAddr.x, d.linked1.fmapAddr.y))
  //val c = b.queueLowLatency(fa / 8)
  //io.outputs << c
  //val arbiPayload = ConvPortBundle(arbitrated.linked1.timeStep, arbitrated.linked2, arbitrated.linked1.fmapAddr)
  val a = arbitrated.takeWhen(arbitrated.payload.value)
  val b = a.translateWith(ConvPortBundle(a.linked1.timeStep, a.linked2, a.linked1.fmapAddr))
  val c = b.queueLowLatency(fa / 8)
  io.outputs << c
  //io.outputs << arbitrated.takeWhen(arbitrated.payload.value).translateWith(arbiPayload).queueLowLatency(fa / 8)
}


object RamCtrlInst{
  val (channelIn, channelOut, fmapHeight, fmapWidth, kernelHeight, kernelWidth, timeStep) = (16, 32, 16, 16, 5, 5, 8)
  val cfg = Config(channelSize = (channelIn, channelOut), fmapSize = (fmapHeight, fmapWidth),
                   kernelSize = (kernelHeight, kernelWidth), timeStep = timeStep)
  val weightsInit = Array.ofDim[Int](channelOut, channelIn, kernelHeight, kernelWidth)
  for (i <- 0 until channelOut) {
    for (j <- 0 until channelIn) {
      for (m <- 0 until kernelHeight) {
        for (n <- 0 until kernelWidth) {
          weightsInit(i)(j)(m)(n) = scala.util.Random.nextInt(256)
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {
    SpinalVerilog(RamCtrl(cfg, weightsInit)).printPruned()
  }
}
