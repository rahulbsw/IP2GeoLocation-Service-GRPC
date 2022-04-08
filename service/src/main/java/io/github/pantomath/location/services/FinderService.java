package io.github.pantomath.location.services;

import io.github.pantomath.location.common.*;
import io.github.pantomath.location.config.DBConfig;
import io.github.pantomath.location.exception.InitializationException;
import io.github.pantomath.location.exception.LookupException;
import io.github.pantomath.location.store.Store;
import io.grpc.stub.StreamObserver;
import lombok.extern.log4j.Log4j2;

import java.net.URISyntaxException;

@Log4j2
public class FinderService extends FindGrpc.FindImplBase {
    Store[] stores=new Store[2];
    public FinderService() throws URISyntaxException, InitializationException {
        this.stores[0] =Store.create(new DBConfig(DBConfig.TYPE.MAXMIND,
                                            FinderService.class.getResource("/database/GeoLite2-City.mmdb").toURI(),
                                            null,
                                            FinderService.class.getResource("/database/GeoLite2-ASN.mmdb").toURI()
        ));

        this.stores[1] =Store.create(new DBConfig(DBConfig.TYPE.IP2LOCATION,
                FinderService.class.getResource("/database/IP2LOCATION-LITE-DB11.BIN").toURI(),
                null,
                null
        ));
    }

    @Override
    public void location(IPAddressExt request, StreamObserver<LocationResponse> responseObserver) {
            try {
                Location location= locationInternal(request.getIp(), request.getIncludeIsp(), request.getIncludeDomain());
                responseObserver.onNext(LocationResponse.newBuilder().setLocation(location).setIpaddress(request.getIp()).build());
                responseObserver.onCompleted();
            } catch (LookupException e) {
                log.error(e);
                responseObserver.onError(e);
            }
    }

    @Override
    public void city(IPAddress request, StreamObserver<CityResponse> responseObserver) {
            try {
                City city= cityInternal(request.getIp());
                System.out.println(city);
                responseObserver.onNext(CityResponse.newBuilder().setCity(city).setIpaddress(request.getIp()).build());
                responseObserver.onCompleted();
            } catch (LookupException e) {
                log.error(e);
                responseObserver.onError(e);
            }
    }

    @Override
    public void country(IPAddress request, StreamObserver<CountryResponse> responseObserver) {
            try {
                Country country= countryInternal(request.getIp());
                responseObserver.onNext(CountryResponse.newBuilder().setCountry(country).setIpaddress(request.getIp()).build());
                responseObserver.onCompleted();
            } catch (LookupException e) {
                log.error(e);
                responseObserver.onError(e);
            }
    }

    @Override
    public void locations(IPAddressesExt request, StreamObserver<LocationResponses> responseObserver) {
        LocationResponses.Builder responses=LocationResponses.newBuilder();
        for (String ip: request.getIpList()) {
            Location location= Location.getDefaultInstance();
            try{
                location=locationInternal(ip,request.getIncludeIsp(),request.getIncludeDomain());
            } catch (LookupException e) {
                log.error(e);
            }
            responses.addLocations(LocationResponse.newBuilder().setLocation(location).setIpaddress(ip).build());
        }
        responseObserver.onNext(responses.build());
        responseObserver.onCompleted();
    }

    @Override
    public void cities(IPAddresses request, StreamObserver<CityResponses> responseObserver) {
        CityResponses.Builder responses=CityResponses.newBuilder();
        for (String ip: request.getIpList()) {
            City city= City.getDefaultInstance();
            try{
                city=cityInternal(ip);
            } catch (LookupException e) {
                log.error(e);
            }
            responses.addCities(CityResponse.newBuilder().setCity(city).setIpaddress(ip).build());
        }
        responseObserver.onNext(responses.build());
        responseObserver.onCompleted();
    }

    @Override
    public void countries(IPAddresses request, StreamObserver<CountryResponses> responseObserver) {
        CountryResponses.Builder responses=CountryResponses.newBuilder();
        for (String ip: request.getIpList()) {
            Country country= Country.getDefaultInstance();
            try{
                country=countryInternal(ip);
            } catch (LookupException e) {
                log.error(e);
            }
            responses.addCountries(CountryResponse.newBuilder().setCountry(country).setIpaddress(ip).build());
        }
        responseObserver.onNext(responses.build());
        responseObserver.onCompleted();
    }

    @Override
    public void asn(IPAddress request, StreamObserver<ASN> responseObserver) {
        super.asn(request, responseObserver);
    }

    private Location locationInternal(String ip,boolean includeIsp,boolean includeDomain) throws  LookupException{
        Location location= Location.getDefaultInstance();
        for (Store store:stores) {
                location= store.location(ip, includeIsp, includeDomain);
                if(Location.getDefaultInstance().equals(location)) //if not found try other store
                    continue;
        }
        return location;
    }

    private City cityInternal(String ip) throws  LookupException{
        City city= City.getDefaultInstance();
        for (Store store:stores) {
            city= store.city(ip);
            if(City.getDefaultInstance().equals(city)) //if not found try other store
                continue;
        }
        return city;
    }

    private Country countryInternal(String ip) throws  LookupException{
        Country country= Country.getDefaultInstance();
        for (Store store:stores) {
            country= store.country(ip);
            if(Country.getDefaultInstance().equals(country)) //if not found try other store
                continue;
        }
        return country;
    }
}
