/*
 *    Copyright (C) 2019  Consiglio Nazionale delle Ricerche
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as
 *     published by the Free Software Foundation, either version 3 of the
 *     License, or (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package it.cnr.si.cool.jconon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.map.listener.EntryEvictedListener;
import it.cnr.si.cool.jconon.dto.SiperSede;
import it.cnr.si.cool.jconon.exception.SiperException;
import org.apache.commons.httpclient.*;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SiperService implements InitializingBean {

	private static final Logger LOGGER = LoggerFactory
			.getLogger(SiperService.class);

    @Value("${siper.anadip.url}")	
	private String urlAnadip;
    
    @Value("${siper.sedi.url}")    
	private String urlSedi;

    @Value("${siper.username}")
    private String userName;

    @Value("${siper.password}")
    private String password;

	@Value("${siper.cache.timeToLiveSeconds}")
	private Integer siperSediTimeToLiveSeconds;

    public static final String SIPER_MAP_NAME = "sedi-siper";

    @Autowired
    private HazelcastInstance hazelcastInstance;

    public JsonObject getAnagraficaDipendente(String username) {
		// Create an instance of HttpClient.
		JsonElement json = null;

		if (username == null || urlAnadip == null) {
			LOGGER.error("Parameter Url and Matricola are required.");
		} else {

            String uri = urlAnadip + '/' + username;

            try {

                HttpMethod method = new GetMethod(uri);

				HttpClient httpClient = new HttpClient();
				Credentials credentials = new UsernamePasswordCredentials(userName, password);
				httpClient.getState().setCredentials(AuthScope.ANY, credentials);
                httpClient.executeMethod(method);

                int statusCode = httpClient.executeMethod(method);

				if (statusCode != HttpStatus.SC_OK) {
					LOGGER.error("Recupero dati da Siper fallito per la matricola "
							+ username
							+ "."
							+ " dalla URL:"
							+ urlAnadip
							+ " [" + statusCode + "]");
					return null;
				} else {
					// Read the response body.

                    String jsonString = method.getResponseBodyAsString();

					json = new JsonParser().parse(jsonString);
				}
			} catch (JsonParseException e) {
				LOGGER.error("Errore in fase di recupero dati da Siper fallito per la matricola "
                        + username
                        + " - "
                        + e.getMessage()
                        + " dalla URL:"
                        + urlAnadip, e);
			} catch (IOException e) {
                LOGGER.error("error in HTTP request " + uri, e);
            }
        }
		return (JsonObject) json;
	}


	@Override
	public void afterPropertiesSet() throws Exception {

    	LOGGER.info("cache {} timeToLive: {}", SIPER_MAP_NAME, siperSediTimeToLiveSeconds);

        hazelcastInstance
                .getConfig()
                .getMapConfig(SIPER_MAP_NAME)
                .setTimeToLiveSeconds(siperSediTimeToLiveSeconds);

		IMap<Object, Object> m = hazelcastInstance.getMap(SIPER_MAP_NAME);

		m.addEntryListener((EntryEvictedListener) event -> {
			LOGGER.warn("clearing siper cache");
			m.clear();
		}, false);

	}

    public Collection<SiperSede> cacheableSiperSedi() {
		return sediSiper()
				.entrySet()
				.stream()
				.sorted((e1, e2) -> e1.getKey().compareTo(e2.getKey()))
    			.map(stringSiperSedeEntry -> stringSiperSedeEntry.getValue())
				.collect(Collectors.toList());
    }



    private Map<String, SiperSede> sediSiper() {

		IMap<String, SiperSede> cache = hazelcastInstance.getMap(SIPER_MAP_NAME);

		if (cache.isEmpty()) {
			LOGGER.info("siper cache is empty");

			Map<String, SiperSede> map = sediSiper(Optional.empty())
					.stream()
					.filter(siperSede -> Optional.ofNullable(siperSede.getIndirizzo())
							.filter(s -> !s.equals("SEDE DA UTILIZZARE"))
							.isPresent())
					.collect(Collectors.toMap(SiperSede::getSedeId, Function.identity()));


			cache.putAll(map);

			LOGGER.info("siper map contains {} sedi", map.size());

			return map;

		} else {
			LOGGER.info("siper cache contains {} items", cache.size());
			return cache;
		}

	}

	@Cacheable(SIPER_MAP_NAME)
    public Optional<SiperSede> cacheableSiperSede(String key) {
        LOGGER.info("evaluating key {}", key);
		Map<String, SiperSede> sediSiper = sediSiper();
		return Optional.ofNullable(sediSiper.get(key));
    }


	private List<SiperSede> sediSiper(Optional<String> sede) {

		RestTemplate rt = new RestTemplate();

		Charset charset = Charset.forName("UTF-8");
		StringHttpMessageConverter stringHttpMessageConverter = new StringHttpMessageConverter(charset);
		rt.getMessageConverters().add(0, stringHttpMessageConverter);

		MultiValueMap<String, String> urlVariables = new LinkedMultiValueMap<>();
		sede.ifPresent(value -> urlVariables.put("sedeId", Arrays.asList(value)));

		URI uri = UriComponentsBuilder
				.fromUriString(urlSedi)
				.queryParams(urlVariables)
				.build()
				.toUri();

		LOGGER.info("siper uri: {}", uri);

		try {
			String json = rt.getForObject(uri, String.class);
			LOGGER.debug("siper data: {}", json);
			ObjectMapper objectMapper = new ObjectMapper();
			SiperSede[] sedi = objectMapper.readValue(json, SiperSede[].class);
			List<SiperSede> siperSedes = new ArrayList<SiperSede>(); 
			siperSedes.addAll(Arrays.asList(sedi));

			SiperSede s1 = new SiperSede();
			s1.setDescrizione("AMMINISTRAZIONE CENTRALE");
			s1.setIndirizzo("");
			s1.setCitta("ROMA");
			s1.setSedeId("-1");

			SiperSede s2 = new SiperSede();
			s2.setSedeId("-2");
			s2.setIndirizzo("");
			s2.setDescrizione("STRUTTURE/ ISTITUTI DEL CONSIGLIO NAZIONALE DELLE RICERCHE");
			s2.setCitta("ITALIA");

			SiperSede s3 = new SiperSede();
			s3.setSedeId("-3");
			s3.setDescrizione("ISTITUTI C/O AREA DELLA RICERCA – MONTELIBRETTI");
			s3.setIndirizzo("VIA SALARIA KM 29,300 C.P. 10");
			s3.setCitta("MONTEROTONDO (RM)");

			SiperSede s4 = new SiperSede();
			s4.setSedeId("-4");
			s4.setDescrizione("DIPARTIMENTO DI SCIENZE BIOAGROALIMENTARI - INFRASTRUTTURA DI METAPONTO");
			s4.setIndirizzo("");
			s4.setCitta("METAPONTO");

			SiperSede s5 = new SiperSede();
			s5.setSedeId("-5");
			s5.setDescrizione("ISTITUTI C/O AREA DELLA RICERCA DI PISA");
			s5.setIndirizzo("VIA GIUSEPPE MORUZZI 1");
			s5.setCap("56124");
			s5.setCitta("PISA");

			siperSedes.add(s1);
			siperSedes.add(s2);
			siperSedes.add(s3);
			siperSedes.add(s4);
			siperSedes.add(s5);

			return siperSedes;

		} catch (IOException e) {
			throw new SiperException("unable to get sedi siper", e);
		} catch (HttpClientErrorException _ex) {
			LOGGER.error("Cannot find sedi", _ex);
			return Collections.emptyList();
		}
	}
}
