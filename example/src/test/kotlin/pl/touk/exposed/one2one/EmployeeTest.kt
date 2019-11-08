package pl.touk.exposed.one2one

import org.assertj.core.api.Assertions
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Before
import org.junit.Test

class EmployeeTest {

    @Before
    fun connect() {
        Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
    }

    @Test
    fun shouldHandleOneToOne() {
        transaction {
            SchemaUtils.create(EmployeeInfoTable, EmployeeTable, ParkingSpotTable)

            // given
            val employee = Employee().let { employee ->
                val id = EmployeeTable.insert {}[EmployeeTable.id]
                employee.copy(id = id)
            }

            val employeeInfo = EmployeeInfoTable.insert(EmployeeInfo(login = "admin", employee = employee))

            //when
            val employees = (EmployeeTable leftJoin EmployeeInfoTable)
                    .select { EmployeeInfoTable.login.regexp("[a-z]{5}") }
                    .toEmployeeList()

            val fullEmployee = employee.copy(employeeInfo = employeeInfo)

            //then
            Assertions.assertThat(employees).containsOnly(fullEmployee)
        }
    }

    @Test
    fun shouldHandleMultipleLevelOneToOne() {
        transaction {
            SchemaUtils.create(EmployeeInfoTable, EmployeeTable, ParkingSpotTable)

            // given
            val employee = Employee().let { employee ->
                val id = EmployeeTable.insert {}[EmployeeTable.id]
                employee.copy(id = id)
            }

            val employeeInfo = EmployeeInfo(login = "admin", employee = employee)
                    .let { employeeInfo -> EmployeeInfoTable.insert(employeeInfo) }

            val parkingSpot = ParkingSpot(code = "C12345", employeeInfo = employeeInfo)
                    .let(ParkingSpotTable::insert)

            //when
            val employees = (EmployeeTable leftJoin EmployeeInfoTable leftJoin ParkingSpotTable).selectAll().toEmployeeList()
            val fullEmployee = employee.copy(employeeInfo = employeeInfo.copy(parkingSpot = parkingSpot))

            //then
            Assertions.assertThat(employees).containsOnly(fullEmployee)
        }
    }
}
