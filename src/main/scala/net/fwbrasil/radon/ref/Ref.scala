package net.fwbrasil.radon.ref

import java.util.concurrent.locks._
import net.fwbrasil.radon.transaction.TransactionContext
import net.fwbrasil.radon.transaction.Transaction
import net.fwbrasil.radon.util.Lockable
import net.fwbrasil.radon.util.ReferenceWeakValueMap
import net.fwbrasil.radon.transaction.NestedTransaction

trait Source[+T] {
	def unary_! = get.getOrElse(null.asInstanceOf[T])
	def get: Option[T]
}

trait Sink[-T] {
	def :=(value: T) = put(Option(value))
	def put(value: Option[T]): Unit
}

trait RefListener[T] {
	def notifyGet(ref: Ref[T]) = {

	}
	def notifyPut(ref: Ref[T], obj: Option[T]) = {

	}
	def notifyRollback(ref: Ref[T]) = {

	}
	def notifyCommit(ref: Ref[T]) = {

	}
}

class Ref[T](pValueOption: Option[T])(implicit val context: TransactionContext)
		extends Source[T] with Sink[T] with Lockable with java.io.Serializable {

	import context.transactionManager._

	def this(pValue: T)(implicit context: TransactionContext) = this(Option(pValue))
	def this()(implicit context: TransactionContext) = this(None)

	private[this] var _refContent: RefContent[T] = new RefContent(None, 0l, 0l, false)

	@transient
	private[this] var _weakListenersMap: ReferenceWeakValueMap[Int, RefListener[T]] = _

	def weakListenersMap = {
		if (_weakListenersMap == null)
			_weakListenersMap = ReferenceWeakValueMap[Int, RefListener[T]]()
		else
			_weakListenersMap
		_weakListenersMap
	}

	@transient
	private[radon] val creationTransaction = getRequiredTransaction

	def getRequiredTransaction =
		getRequiredActiveTransaction

	def getTransaction =
		getActiveTransaction

	put(pValueOption)

	private[fwbrasil] def refContent =
		_refContent

	private[fwbrasil] def setRefContent(value: Option[T]): Unit =
		setRefContent(value, readTimestamp, writeTimestamp, destroyedFlag)

	private[fwbrasil] def setRefContent(pValue: Option[T], pReadTimestamp: Long, pWriteTimestamp: Long, pDestroyedFlag: Boolean): Unit = {
		if (_weakListenersMap != null)
			for (listener <- _weakListenersMap.values)
				listener.notifyCommit(this)
		_refContent = new RefContent[T](pValue, pReadTimestamp, pWriteTimestamp, pDestroyedFlag)
	}

	private[fwbrasil] def destroyInternal =
		setRefContent(None, readTimestamp, writeTimestamp, true)

	private[radon] def readTimestamp = refContent.readTimestamp
	private[radon] def writeTimestamp = refContent.writeTimestamp
	private[radon] def destroyedFlag = refContent.destroyedFlag
	private[radon] def isCreating =
		writeTimestamp == 0 &&
			creationTransaction != null &&
			!creationTransaction.transient

	def get: Option[T] = {
		val result = getRequiredTransaction.get(this)
		if (_weakListenersMap != null)
			for (listener <- _weakListenersMap.values)
				listener.notifyGet(this)
		result
	}

	def put(pValue: Option[T]): Unit = {
		val value = if (pValue == null) None else pValue
		if (_weakListenersMap == null)
			getRequiredTransaction.put(this, value)
		else {
			import context._
			transactional(nested) {
				getRequiredTransaction.put(this, value)
				for (listener <- _weakListenersMap.values)
					listener.notifyPut(this, value)
			}
		}
	}

	private[radon] def notifyRollback =
		if (_weakListenersMap != null)
			for (listener <- _weakListenersMap.values)
				listener.notifyRollback(this)

	def destroy: Unit =
		getRequiredTransaction.destroy(this)

	def isDestroyed: Boolean =
		getRequiredTransaction.isDestroyed(this)

	def isDirty: Boolean =
		getRequiredTransaction.isDirty(this)

	def addWeakListener(listener: RefListener[T]) =
		weakListenersMap += (listener.hashCode() -> listener)

	protected def snapshot =
		if (getTransaction.isDefined) {
			if (!isDestroyed)
				get
			else
				Option("destroyed")
		} else if (!_refContent.destroyedFlag)
			_refContent.value
		else
			Option("destroyed")

	override def toString =
		"Ref(" + snapshot + ")"
}

object Ref {
	def apply[T](value: T)(implicit context: TransactionContext) = new Ref(value)
	def apply[T](implicit context: TransactionContext) = new Ref
}

case class RefContent[T](
	value: Option[T],
	readTimestamp: Long,
	writeTimestamp: Long,
	destroyedFlag: Boolean)

trait RefContext {

	type Ref[T] = net.fwbrasil.radon.ref.Ref[T]

	implicit def refToValue[A](ref: Ref[A]): A =
		if (ref == null)
			null.asInstanceOf[A]
		else !ref

	implicit def valueToRef[A](value: A)(implicit context: TransactionContext): Ref[A] =
		new Ref(value)
}