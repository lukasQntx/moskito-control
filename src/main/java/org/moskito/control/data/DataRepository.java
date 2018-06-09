package org.moskito.control.data;

import net.anotheria.util.StringUtils;
import org.configureme.ConfigurationManager;
import org.moskito.control.config.MoskitoControlConfiguration;
import org.moskito.control.config.datarepository.DataProcessingConfig;
import org.moskito.control.config.datarepository.DataRepositoryConfig;
import org.moskito.control.config.datarepository.ProcessorConfig;
import org.moskito.control.data.preprocessors.DataPreprocessor;
import org.moskito.control.data.processors.DataProcessor;
import org.moskito.control.data.test.JSONRetriever;
import org.moskito.control.data.test.JSONValueMapping;
import org.moskito.control.data.test.MoSKitoValueMapping;
import org.moskito.control.data.test.TestMoSKitoRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TODO comment this class
 *
 * @author lrosenberg
 * @since 04.06.18 13:56
 */
public class DataRepository {

	private static Logger log = LoggerFactory.getLogger(DataRepository.class);

	private volatile Map<String, String> dataMap = Collections.unmodifiableMap(Collections.emptyMap());

	private ConcurrentMap<String, Class<DataProcessor>> processorClasses = new ConcurrentHashMap<>();
	private ConcurrentMap<String, Class<DataPreprocessor>> preprocessorClasses = new ConcurrentHashMap<>();

	private List<DataRetriever> retrievers = new LinkedList<>();
	private List<DataProcessor> processors = new LinkedList<>();
	private List<DataPreprocessor> preprocessors = new LinkedList<>();

	public static final DataRepository getInstance(){
		return DataRepositoryInstanceHolder.instance;
	}

	public Map<String,String> getData(){
		return dataMap;
	}

	public void update(Map<String, String> newData){
		dataMap = Collections.unmodifiableMap(newData);
	}

	static class DataRepositoryInstanceHolder{
		private static DataRepository instance = new DataRepository();
		//start updater.
		private static DataUpdater updater = new DataUpdater();
		static {
			instance.configure();
			instance.testFilling();
		}
	}

	public List<DataRetriever> getRetrievers() {
		return retrievers;
	}

	public List<DataProcessor> getProcessors() {
		return processors;
	}

	public List<DataPreprocessor> getPreprocessors(){
		return preprocessors;
	}


	public void addDataRetriever(DataRetriever aRetriever){
		retrievers.add(aRetriever);
	}

	public void addDataProcessor(DataProcessor dataProcessor){
		processors.add(dataProcessor);
	}

	public void addDataPreprocessor(DataPreprocessor dataPreprocessor){
		preprocessors.add(dataPreprocessor);
	}

	private void configure(){
		DataRepositoryConfig repositoryConfig = new DataRepositoryConfig();
		ConfigurationManager.INSTANCE.configure(repositoryConfig);


		DataProcessingConfig processingConfig = MoskitoControlConfiguration.getConfiguration().getDataprocessing();
		System.out.println("DataProcessingConfig: "+processingConfig);
		//configure processors - classes.
		processorClasses.clear();
		if (repositoryConfig.getProcessors()!=null && repositoryConfig.getProcessors().length>0) {
			for (ProcessorConfig pc : repositoryConfig.getProcessors()) {
				try {
					Class<DataProcessor> processorClass = (Class<DataProcessor>) Class.forName(pc.getClazz());
					processorClasses.put(pc.getName(), processorClass);
				} catch (ClassNotFoundException e) {
					log.error("Class " + pc.getClazz() + " for processor " + pc.getName() + " not found", e);
				}
			}
			log.info("Configured processors: " + processorClasses);
		}

		//configure processors - processing.
		processors = new CopyOnWriteArrayList<>();
		for (String processingLine : processingConfig.getProcessing()){
			String tokens[] = StringUtils.tokenize(processingLine, ' ');
			//TODO if more then 3 tokens, sum all the following tokens back into 3rd
			String processorName = tokens[0];
			String variableName  = tokens[1];
			String parameter     = tokens[2];
			Class<DataProcessor> clazz = processorClasses.get(processorName);
			if (clazz==null){
				log.error("Can't setup processing "+processingLine+" processor "+processorName+" is not configured");
				continue;
			}

			try {
				DataProcessor processor = clazz.newInstance();
				processor.configure(variableName, parameter);
				addDataProcessor(processor);
			} catch (InstantiationException |IllegalAccessException e) {
				log.error("Can't instantiate processor "+processorName+" -> "+clazz+" -> ", e);
			} catch(Exception any){
				log.error("Can't configure processor "+processorName+", of class "+clazz+", with ("+variableName+", "+parameter+")", any);
			}
		}
		log.info("Configured processing: "+processors);

		//configure preprocessors - classes.
		preprocessorClasses.clear();
		if (repositoryConfig.getPreprocessors()!=null && repositoryConfig.getPreprocessors().length>0){
			for (ProcessorConfig pc : repositoryConfig.getPreprocessors()){
				try{
					Class<DataPreprocessor> preprocessorClass = (Class<DataPreprocessor>)Class.forName(pc.getClazz());
					preprocessorClasses.put(pc.getName(), preprocessorClass);
				}catch(ClassNotFoundException e){
					log.error("Class "+pc.getClazz()+" for preprocessor "+pc.getName()+" not found", e);
				}
			}
			log.info("Configured preprocessors: "+preprocessorClasses);
		}



		//Configure preprocessors - processing.
		preprocessors = new CopyOnWriteArrayList<>();
		for (String preprocessingLine : processingConfig.getPreprocessing()){
			String tokens[] = StringUtils.tokenize(preprocessingLine, ' ');
			//TODO if more then 3 tokens, sum all the following tokens back into 3rd
			String preprocessorName = tokens[0];
			String variableName  = tokens[1];
			String parameter     = tokens[2];
			Class<DataPreprocessor> clazz = preprocessorClasses.get(preprocessorName);
			if (clazz==null){
				log.error("Can't setup processing "+preprocessingLine+" processor "+preprocessorName+" is not configured");
				continue;
			}

			try {
				DataPreprocessor preprocessor = clazz.newInstance();
				preprocessor.configure(variableName, parameter);
				addDataPreprocessor(preprocessor);
			} catch (InstantiationException |IllegalAccessException e) {
				log.error("Can't instantiate processor "+preprocessorName+" -> "+clazz+" -> ", e);
			}
		}
		log.info("Configured preprocessing: "+preprocessors);


	}

	private void testFilling(){
		//addDataRetriever(new TestDataRetriever());
		addDataRetriever(createTestAddMosKitoMappings("hamburg"));
		addDataRetriever(createTestAddMosKitoMappings("munich"));
		addDataRetriever(createTestAddMosKitoMappings("bedcon"));

		addDataRetriever(createJsonTestRetrieverPayment());
		addDataRetriever(createJsonTestRetrieverRegs());
	}

	private JSONRetriever createJsonTestRetrieverPayment(){
		JSONRetriever retriever = new JSONRetriever();
		retriever.setUrl("https://extapi.thecasuallounge.com/extapi/api/v1/data/paymentsPerDay");
		retriever.setMappings(Arrays.asList(new JSONValueMapping[]{
			new JSONValueMapping("$.results.payments[2].all.count", "payments.count.yesterday"),
				new JSONValueMapping("$.results.payments[0].all.count", "payments.count.today"),
				new JSONValueMapping("$.results.payments[1].all.count", "payments.count.sameYesterday"),
				new JSONValueMapping("$.results.payments[2].all.revenue", "payments.revenue.yesterday"),
				new JSONValueMapping("$.results.payments[0].all.revenue", "payments.revenue.today"),
				new JSONValueMapping("$.results.payments[1].all.revenue", "payments.revenue.sameYesterday")
		}));

		return retriever;
	}

	private JSONRetriever createJsonTestRetrieverRegs(){
		JSONRetriever retriever = new JSONRetriever();
		retriever.setUrl("https://extapi.thecasuallounge.com/extapi/api/v1/data/registrationsPerDay");
		retriever.setMappings(Arrays.asList(new JSONValueMapping[]{
				new JSONValueMapping("$.results.registrations[2].all.count", "reg.total.yesterday"),
				new JSONValueMapping("$.results.registrations[0].all.count", "reg.total.today"),
				new JSONValueMapping("$.results.registrations[1].all.count", "reg.total.sameYesterday"),
				new JSONValueMapping("$.results.registrations[2].all.male", "reg.male.yesterday"),
				new JSONValueMapping("$.results.registrations[0].all.male", "reg.male.today"),
				new JSONValueMapping("$.results.registrations[1].all.male", "reg.male.sameYesterday")
		}));



		return retriever;
	}

	private TestMoSKitoRetriever createTestAddMosKitoMappings(String prefix){

		MoSKitoValueMapping mapping1 = new MoSKitoValueMapping();
		mapping1.setProducerName("ShopService");
		mapping1.setStatName("placeOrder");
		mapping1.setValueName("req");
		mapping1.setIntervalName("1m");
		mapping1.setTimeUnitName("MILLISECONDS");
		mapping1.setTargetVariableName(prefix+".orderCount");

		MoSKitoValueMapping mapping2 = new MoSKitoValueMapping();
		mapping2.setProducerName("sales");
		mapping2.setStatName("cumulated");
		mapping2.setValueName("Volume");
		mapping2.setIntervalName("1h");
		mapping2.setTimeUnitName("MILLISECONDS");
		mapping2.setTargetVariableName(prefix+".earnings");

		MoSKitoValueMapping mapping3 = new MoSKitoValueMapping();
		mapping3.setProducerName("SessionCount");
		mapping3.setStatName("Sessions");
		mapping3.setValueName("Cur");
		mapping3.setIntervalName("default");
		mapping3.setTimeUnitName("MILLISECONDS");
		mapping3.setTargetVariableName(prefix+".sessions");

		MoSKitoValueMapping mapping4 = new MoSKitoValueMapping();
		mapping4.setProducerName("RequestURI");
		mapping4.setStatName("cumulated");
		mapping4.setValueName("Req");
		mapping4.setIntervalName("1h");
		mapping4.setTimeUnitName("MILLISECONDS");
		mapping4.setTargetVariableName(prefix+".requests");

		TestMoSKitoRetriever r = new  TestMoSKitoRetriever("http://burgershop-"+prefix+".demo.moskito.org/burgershop/moskito-inspect-rest", mapping1, mapping2, mapping3, mapping4);
		return r;

	}
}