package org.fao.geonet.api.mapservices;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.fao.geonet.ApplicationContextHolder;
import org.fao.geonet.domain.mapservices.MapService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

@RequestMapping(value = {
    "/{portal}/api/mapservices"
})
@Api(value = "mapservices",
    tags = "mapservices",
    description = "Mapservices related operations")
@Controller("mapservices")
public class MapServicesApi {
    @ApiOperation(
        value = "Get mapservices",
        notes = "Return the list of mapservices. mapservices are used to identify secured map services.")

    @RequestMapping(
        method = RequestMethod.GET,
        produces = {
            MediaType.APPLICATION_JSON_VALUE
        })
    public
    @ResponseBody
    @ResponseStatus(HttpStatus.OK)
    List<MapService> getMapservices() throws Exception {
        @SuppressWarnings("unchecked")
        List<MapService> mapServiceList = ApplicationContextHolder.get().getBean("securedMapServices", List.class);

        return mapServiceList;
    }
}
