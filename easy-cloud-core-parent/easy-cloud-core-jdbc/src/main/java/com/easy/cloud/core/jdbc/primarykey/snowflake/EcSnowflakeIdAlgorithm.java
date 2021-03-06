package com.easy.cloud.core.jdbc.primarykey.snowflake;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;

import com.easy.cloud.core.common.date.utils.EcDateUtils;
import com.easy.cloud.core.common.map.utils.EcMapUtils;

/**
 * Twitter_Snowflake<br>
 * SnowFlake的结构如下(每部分用-分开):<br>
 * 0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 -
 * 000000000000 <br>
 * twitter的雪花算法，是将id按二进制比特位切割，不同的位区间，表示不同的含义，也即是不同位区间的值生成方式不同，从而生成唯一的id。
 * 如位区间可分为时间位区间、集群位区间、机器位区间、自增位区间，这样可在不同时间内、不同集群、 不同机器间，生成全局唯一的id。
 * 在此以生成64位（即long型）为例进行介绍（其实区间位可以根据具体的业务需要自行指定）。 1、位区间化分
 * 最高位（即第64位，从右向左数）为符号位，不使用；
 * 41位（第23位到第63位）为时间位，可使用个数为2199023255551个，以毫秒为单位，大约69.5年
 * 5位（第18位到第22位）为集群位，可使用个数为32个； 5位（第13位到第17位）为机器位，可使用个数为32个；
 * 12位（第1位到第12位）为序列号位，即是从0开始自增，可使用个数为4096个
 * SnowFlake的优点是，整体上按照时间自增排序，并且整个分布式系统内不会产生ID碰撞(由数据中心ID和机器ID作区分)，并且效率较高，经测试，
 * SnowFlake每秒能够产生26万ID左右。
 * 
 * 使用EcKeyGeneratorConfig配置类生成EcSnowflakeIdWorkerBO示例
 */
public class EcSnowflakeIdAlgorithm {

	private static class LazyHolder {
		private static final EcSnowflakeIdAlgorithm SNOWFLAKE_ALGORITHM = new EcSnowflakeIdAlgorithm();

	}

	private EcSnowflakeIdAlgorithm() {

	}

	public static final EcSnowflakeIdAlgorithm getSingleInstance(long workerId, long datacenterId) {
		return LazyHolder.SNOWFLAKE_ALGORITHM.buidWorkerIdAndDatacenterId(workerId, datacenterId);
	}

	// ==============================Fields===========================================
	/** 开始时间截 (2015-01-01) */
	private final long twepoch = 1420041600000L;

	/** 机器id所占的位数 */
	private final long workerIdBits = 4L;

	/** 数据标识id所占的位数 */
	private final long datacenterIdBits = 4L;

	/** 支持的最大机器id，结果是16 (这个移位算法可以很快的计算出几位二进制数所能表示的最大十进制数) */
	private final long maxWorkerId = -1L ^ (-1L << (workerIdBits));

	/** 支持的最大数据标识id，结果是16 */
	private final long maxDatacenterId = -1L ^ (-1L << (datacenterIdBits));

	/** 序列在id中占的位数 */
	private final long sequenceBits = 14L;

	/** 机器ID向左移14位 */
	private final long workerIdShift = sequenceBits;

	/** 数据标识id向左移18位(14+4) */
	private final long datacenterIdShift = sequenceBits + workerIdBits;

	/** 时间截向左移22位(3+3+16) */
	private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;

	/** 生成序列的掩码，这里为2的14次方 (0b11111111111111=0xfff=16384) */
	private final long sequenceMask = -1L ^ (-1L << sequenceBits);

	/** 工作机器ID(0~15) */
	private long workerId;

	/** 数据中心ID(0~15) */
	private long datacenterId;

	/** 毫秒内序列(0~16383) */
	private volatile long sequence = 0L;

	/** 上次生成ID的时间截 */
	private volatile long lastTimestamp = -1L;

	// ==============================Constructors=====================================

	private EcSnowflakeIdAlgorithm buidWorkerIdAndDatacenterId(long workerId, long datacenterId) {
		if (workerId > maxWorkerId || workerId < 0) {
			throw new IllegalArgumentException(
					String.format("worker Id can't be greater than %d or less than 0", maxWorkerId));
		}
		if (datacenterId > maxDatacenterId || datacenterId < 0) {
			throw new IllegalArgumentException(
					String.format("datacenter Id can't be greater than %d or less than 0", maxDatacenterId));
		}
		this.workerId = workerId;
		this.datacenterId = datacenterId;
		return this;
	}

	// ==============================Methods==========================================
	/**
	 * 获得下一个ID (该方法是线程安全的)
	 * 
	 * @return SnowflakeId
	 */
	public synchronized long nextId() {
		long timestamp = EcDateUtils.getCurrentTimeMillis();
		// 如果当前时间小于上一次ID生成的时间戳，说明系统时钟回退过这个时候应当抛出异常
		if (timestamp < lastTimestamp) {
			throw new RuntimeException(String.format(
					"Clock moved backwards.  Refusing to generate id for %d milliseconds", lastTimestamp - timestamp));
		}
		// 如果是同一时间生成的，则进行毫秒内序列
		if (lastTimestamp == timestamp) {
			sequence = (sequence + 1) & sequenceMask;
			// 毫秒内序列溢出
			if (sequence == 0) {
				// // 阻塞到下一个毫秒,获得新的时间戳
				timestamp = tilNextMillis(lastTimestamp);
			}
		} else {
			// 时间戳改变，毫秒内序列重置
			sequence = 0L;
		}
		// 上次生成ID的时间截
		lastTimestamp = timestamp;
		// 移位并通过或运算拼到一起组成64位的ID
		return ((timestamp - twepoch) << timestampLeftShift) //
				| (datacenterId << datacenterIdShift) //
				| (workerId << workerIdShift) //
				| sequence;
	}

	/**
	 * 阻塞到下一个毫秒，直到获得新的时间戳
	 * 
	 * @param lastTimestamp
	 *            上次生成ID的时间截
	 * @return 当前时间戳
	 */
	protected long tilNextMillis(long lastTimestamp) {
		long timestamp = EcDateUtils.getCurrentTimeMillis();
		while (timestamp <= lastTimestamp) {
			timestamp = EcDateUtils.getCurrentTimeMillis();
		}
		return timestamp;
	}

	// 算法测试
	public static void main(String[] args) throws InterruptedException, ExecutionException {
		Map<Long, Long> nextIdContainer = EcMapUtils.newConcurrentHashMap();
		EcSnowflakeIdAlgorithm snowflakeIdAlgorithm = EcSnowflakeIdAlgorithm.getSingleInstance(0, 0);
		for (int j = 0; j < 3; ++j) {
			int threadNumber = 1000;
			final CountDownLatch countDownLatch = new CountDownLatch(threadNumber);
			long beginTime = System.currentTimeMillis();
			for (int i = 0; i < threadNumber; i++) {
				new Thread(new Runnable() {

					@Override
					public void run() {
						for (int j = 0; j < 5000; ++j) {
							// System.out.println(snowflakeIdAlgorithm.nextIdNew());
							// System.out.println(snowflakeIdAlgorithm.nextId());
							// nextIdContainer.put(snowflakeIdAlgorithm.nextIdNew(),
							// 1l);
							// nextIdContainer.put(snowflakeIdAlgorithm.nextId(),
							// 1l);
							snowflakeIdAlgorithm.nextId();
							// snowflakeIdAlgorithm.nextIdNew();
						}
						countDownLatch.countDown();
					}
				}).start();
			}

			countDownLatch.await();
			System.out.println("nextIdContainer的数量为" + nextIdContainer.size());
			// System.out.println("atomicLastTimestamp的为" +
			// atomicLastTimestamp.get());
			// System.out.println("atomicSequence的为" + atomicSequence.get());
			System.out.println("lastTimestamp的值为：" + snowflakeIdAlgorithm.lastTimestamp);
			System.out.println("sequence的值为：" + snowflakeIdAlgorithm.sequence);
			System.out.println("main thread finished!!时间为：" + (System.currentTimeMillis() - beginTime));
			System.out.println(EcDateUtils.getCurrentTimeMillis());
		}
		System.out.println(Math.pow(2, 14));
	}
}
