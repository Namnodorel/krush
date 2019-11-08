package pl.touk.exposed.one2many.bidi

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upperCase
import org.junit.Before
import org.junit.Test

class CustomerTest {

    @Before
    fun connect() {
        Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")
    }

    @Test
    fun shouldInsertAndSelectBidiOneToMany() {
        transaction {
            SchemaUtils.create(CustomerTable, PhoneTable, AddressTable)

            // given
            val currentAddress = Address(city = "Warsaw", street = "Suwak", houseNo = "12/14", apartmentNo = "206")

            val customer = CustomerTable.insert(Customer(name = "TouK", age = 13, currentAddress = currentAddress)).copy(currentAddress = null)

            val phone = PhoneTable.insert(Phone(number = "777 888 999", customer = customer))
            val address = AddressTable.insert(currentAddress.copy(customer = customer))

            // then
            val customers = (CustomerTable leftJoin PhoneTable leftJoin AddressTable)
                    .select { PhoneTable.number.isNotNull() and (AddressTable.street.upperCase() eq "SUWAK")}
                    .toCustomerList()

            // then
            val fullCustomer = customer.copy(addresses = listOf(address), phones = listOf(phone))
            assertThat(customers).containsOnly(fullCustomer)
        }
    }
}
